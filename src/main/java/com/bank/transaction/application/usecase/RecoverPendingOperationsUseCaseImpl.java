package com.bank.transaction.application.usecase;

import com.bank.transaction.application.port.in.RecoverPendingOperationsUseCase;
import com.bank.transaction.application.port.out.AccountMovementPort;
import com.bank.transaction.application.port.out.TransactionEventPublisherPort;
import com.bank.transaction.application.port.out.TransactionRepositoryPort;
import com.bank.transaction.application.port.out.TransferRepositoryPort;
import com.bank.transaction.application.port.out.UnitOfWorkPort;
import com.bank.transaction.application.view.RecoveryResult;
import com.bank.transaction.domain.event.TransactionFailed;
import com.bank.transaction.domain.event.TransactionRegistered;
import com.bank.transaction.domain.event.TransferCompleted;
import com.bank.transaction.domain.event.TransferFailed;
import com.bank.transaction.domain.model.MovementOutcome;
import com.bank.transaction.domain.model.ProductRef;
import com.bank.transaction.domain.model.ProductType;
import com.bank.transaction.domain.model.ReversalOutcome;
import com.bank.transaction.domain.model.Transaction;
import com.bank.transaction.domain.model.TransactionReversal;
import com.bank.transaction.domain.model.TransactionStatus;
import com.bank.transaction.domain.model.TransactionType;
import com.bank.transaction.domain.model.Transfer;
import com.bank.transaction.domain.model.TransferStatus;
import io.reactivex.rxjava3.core.Single;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Regla 14: reintenta con la misma operationId. Separa dos universos: movimientos simples
 * (DEPOSIT/WITHDRAWAL) PENDING, y transferencias detenidas a medio camino (data-model.md
 * 2.6: {@code STARTED} esperando el resultado del débito, {@code SOURCE_DEBITED} esperando
 * el crédito, o {@code COMPENSATING} esperando que la reversa termine de aplicarse). No
 * reintenta las patas TRANSFER_OUT/TRANSFER_IN como movimientos sueltos: eso se hace a
 * través de la transferencia, para no avanzar un lado sin el otro.
 *
 * <p>Para {@code STARTED} (regla 14, fila de {@code Transfer}): como {@code
 * StartTransferUseCaseImpl.openAndDebit} guarda el {@code Transfer} y su {@code
 * Transaction} {@code <op>-OUT} en la MISMA transacción de Mongo ({@code UnitOfWorkPort}),
 * en la práctica esa {@code Transaction} siempre existe ya cuando el {@code Transfer}
 * quedó {@code STARTED} de verdad (una caída a medio camino de esa escritura atómica no
 * deja ver ninguna de las dos mitades). El caso "sin `<op>-OUT` (caída entre guardados)"
 * que data-model.md igual contempla es, por eso, solo defensivo aquí — {@link
 * #createMissingDebitPending} lo cubre por si acaso, pero no es el camino esperado.
 *
 * <p>Repite a propósito una porción de la lógica de StartTransferUseCaseImpl (crédito y
 * reversa): aquí nunca se lanza una excepción, se cuenta y se sigue con la siguiente
 * operación pendiente, que es una forma de control de flujo bastante distinta como para
 * no forzar un método compartido.
 *
 * <p><b>Aislamiento por elemento (data-model.md 2.6, "cada elemento se procesa aislado: un
 * error no detiene la corrida"):</b> {@link #retryTransaction} y {@link #retryTransfer} se
 * envuelven con {@code onErrorResumeNext} antes de entrar al {@code flatMapSingle} de su
 * respectivo {@code Flowable} (R4 — antes de introducir Mongo real con bloqueo optimista en
 * {@code transfers}, un {@code OptimisticLockingFailureException}/{@code MongoException} real
 * durante un elemento habría interrumpido el {@code Flowable} entero y descartado el resto de
 * la corrida sin procesar). Un movimiento que falla por un error de infraestructura (no por
 * una regla de negocio) se cuenta como no aplicado pero NO se marca {@code FAILED}: se deja
 * {@code PENDING} tal cual estaba, para que el siguiente paso de recuperación lo vuelva a
 * intentar. Una transferencia en la misma situación se cuenta devolviendo su estado actual sin
 * cambios, igual que las ramas {@code switchIfEmpty} ya hacían para "nada que hacer esta
 * vez" — ninguna de las dos rutas requiere guardar nada en Mongo para el elemento fallido, así
 * que no hay nada que deba deshacerse.
 */
public class RecoverPendingOperationsUseCaseImpl implements RecoverPendingOperationsUseCase {

    private final TransactionRepositoryPort transactionRepositoryPort;
    private final TransferRepositoryPort transferRepositoryPort;
    private final AccountMovementPort accountMovementPort;
    private final UnitOfWorkPort unitOfWorkPort;
    private final TransactionEventPublisherPort eventPublisherPort;
    private final int defaultOlderThanMinutes;
    private final int maxCompensationAttempts;
    private final Clock clock;

    public RecoverPendingOperationsUseCaseImpl(TransactionRepositoryPort transactionRepositoryPort,
            TransferRepositoryPort transferRepositoryPort, AccountMovementPort accountMovementPort,
            UnitOfWorkPort unitOfWorkPort, TransactionEventPublisherPort eventPublisherPort,
            int defaultOlderThanMinutes, int maxCompensationAttempts, Clock clock) {
        this.transactionRepositoryPort = transactionRepositoryPort;
        this.transferRepositoryPort = transferRepositoryPort;
        this.accountMovementPort = accountMovementPort;
        this.unitOfWorkPort = unitOfWorkPort;
        this.eventPublisherPort = eventPublisherPort;
        this.defaultOlderThanMinutes = defaultOlderThanMinutes;
        this.maxCompensationAttempts = maxCompensationAttempts;
        this.clock = clock;
    }

    @Override
    public Single<RecoveryResult> execute(Integer olderThanMinutesOverride) {
        Instant threshold = clock.instant().minus(
                Duration.ofMinutes(olderThanMinutesOverride != null ? olderThanMinutesOverride
                        : defaultOlderThanMinutes));
        return recoverTransactions(threshold)
                .flatMap(txCounts -> recoverTransfers(threshold)
                        .map(transferCounts -> new RecoveryResult(txCounts[0], txCounts[1], txCounts[2],
                                transferCounts[0], transferCounts[1], transferCounts[2])));
    }

    // --- movimientos simples -----------------------------------------------------------------

    private Single<int[]> recoverTransactions(Instant threshold) {
        return transactionRepositoryPort.findPendingOlderThan(threshold)
                .filter(tx -> tx.type() == TransactionType.DEPOSIT || tx.type() == TransactionType.WITHDRAWAL)
                .flatMapSingle(pending -> retryTransaction(pending).onErrorResumeNext(error -> Single.just(false)))
                .toList()
                .map(results -> new int[] {
                        results.size(),
                        (int) results.stream().filter(Boolean::booleanValue).count(),
                        (int) results.stream().filter(applied -> !applied).count()});
    }

    private Single<Boolean> retryTransaction(Transaction pending) {
        LocalDate today = LocalDate.now(clock);
        return accountMovementPort.apply(pending.operationId(), pending.product().productId(), pending.type(),
                        pending.amount(), today)
                .flatMap(outcome -> outcome.applied() ? completeTransaction(pending, outcome)
                        : failTransaction(pending, outcome));
    }

    private Single<Boolean> completeTransaction(Transaction pending, MovementOutcome outcome) {
        Transaction completed = pending.complete(outcome.resultingBalance(), clock);
        return transactionRepositoryPort.save(completed)
                .flatMap(saved -> eventPublisherPort.publish(TransactionRegistered.from(saved))
                        .andThen(Single.just(true)));
    }

    private Single<Boolean> failTransaction(Transaction pending, MovementOutcome outcome) {
        Transaction failed = pending.fail(outcome.failureReason(), clock);
        return transactionRepositoryPort.save(failed)
                .flatMap(saved -> eventPublisherPort.publish(TransactionFailed.from(saved))
                        .andThen(Single.just(false)));
    }

    // --- transferencias detenidas --------------------------------------------------------------

    private Single<int[]> recoverTransfers(Instant threshold) {
        return transferRepositoryPort.findInProgressOlderThan(threshold)
                .flatMapSingle(transfer -> retryTransfer(transfer)
                        .onErrorResumeNext(error -> Single.just(transfer.status())))
                .toList()
                .map(results -> new int[] {
                        results.size(),
                        (int) results.stream().filter(status -> status == TransferStatus.COMPLETED
                                || status == TransferStatus.COMPENSATED).count(),
                        (int) results.stream().filter(status -> status == TransferStatus.COMPENSATION_FAILED
                                || status == TransferStatus.FAILED).count()});
    }

    private Single<TransferStatus> retryTransfer(Transfer transfer) {
        return switch (transfer.status()) {
            case STARTED -> retryDebit(transfer);
            case SOURCE_DEBITED -> retryCredit(transfer);
            case COMPENSATING -> retryReversal(transfer);
            default -> Single.just(transfer.status());
        };
    }

    private Single<TransferStatus> retryDebit(Transfer transfer) {
        return transactionRepositoryPort.findByOperationId(transfer.operationId().forTransferOut())
                .switchIfEmpty(createMissingDebitPending(transfer))
                .flatMap(debitTx -> debitTx.status() == TransactionStatus.COMPLETED
                        ? advanceToSourceDebited(transfer, debitTx)
                        : retryDebitApplication(transfer, debitTx));
    }

    /** Defensivo (ver Javadoc de la clase): solo se usa si de verdad falta la Transaction
     *  {@code <op>-OUT} que la escritura atómica de {@code StartTransferUseCaseImpl} debería
     *  haber dejado siempre junto al Transfer STARTED. */
    private Single<Transaction> createMissingDebitPending(Transfer transfer) {
        Transaction debitPending = Transaction.pending(transfer.operationId().forTransferOut(),
                new ProductRef(transfer.sourceAccountId(), ProductType.ACCOUNT), transfer.sourceCustomerId(),
                TransactionType.TRANSFER_OUT, transfer.amount(), transfer.id(), null, null, transfer.description(),
                clock);
        return transactionRepositoryPort.save(debitPending);
    }

    private Single<TransferStatus> retryDebitApplication(Transfer transfer, Transaction debitPending) {
        LocalDate today = LocalDate.now(clock);
        return accountMovementPort.apply(debitPending.operationId(), transfer.sourceAccountId(),
                        TransactionType.TRANSFER_OUT, transfer.amount(), today)
                .flatMap(outcome -> outcome.applied()
                        ? advanceToSourceDebited(transfer, debitPending.complete(outcome.resultingBalance(), clock))
                        : failStartedTransfer(transfer, debitPending, outcome));
    }

    private Single<TransferStatus> advanceToSourceDebited(Transfer transfer, Transaction debitCompleted) {
        Transfer sourceDebited = transfer.sourceDebited(debitCompleted.id(), clock);
        return unitOfWorkPort.saveTransferAndTransaction(sourceDebited, debitCompleted).map(Transfer::status);
    }

    /** STARTED -&gt; FAILED (regla 8): el débito fue rechazado, no hay nada que compensar
     *  todavía, así que sí es un estado terminal — a diferencia de COMPENSATING, este caso
     *  sí publica TransferFailed de una vez. */
    private Single<TransferStatus> failStartedTransfer(Transfer transfer, Transaction debitPending,
                                                          MovementOutcome outcome) {
        Transaction debitFailed = debitPending.fail(outcome.failureReason(), clock);
        Transfer failed = transfer.failed(outcome.failureReason(), clock);
        return unitOfWorkPort.saveTransferAndTransaction(failed, debitFailed)
                .flatMap(saved -> eventPublisherPort.publish(TransferFailed.from(saved))
                        .andThen(Single.just(saved.status())));
    }

    private Single<TransferStatus> retryCredit(Transfer transfer) {
        return transactionRepositoryPort.findByOperationId(transfer.operationId().forTransferIn())
                .switchIfEmpty(createMissingCreditPending(transfer))
                .flatMap(creditPending -> {
                    LocalDate today = LocalDate.now(clock);
                    return accountMovementPort.apply(creditPending.operationId(), transfer.targetAccountId(),
                                    TransactionType.TRANSFER_IN, transfer.amount(), today)
                            .flatMap(outcome -> outcome.applied()
                                    ? completeTransfer(transfer, creditPending, outcome)
                                    : startCompensationFromRecovery(transfer, creditPending, outcome));
                });
    }

    /**
     * {@code StartTransferUseCaseImpl} guarda {@code SOURCE_DEBITED} (con el débito) y luego, en
     * OTRA escritura atómica, la pata {@code <op>-IN} {@code PENDING}: una caída entre las dos deja
     * la transferencia sin pata de crédito. Se crea aquí y se sigue igual que si existiera; aplicar
     * con la misma operationId es idempotente en account-service, así que no hay riesgo de abonar
     * dos veces.
     */
    private Single<Transaction> createMissingCreditPending(Transfer transfer) {
        Transaction creditPending = Transaction.pending(transfer.operationId().forTransferIn(),
                new ProductRef(transfer.targetAccountId(), ProductType.ACCOUNT), transfer.targetCustomerId(),
                TransactionType.TRANSFER_IN, transfer.amount(), transfer.id(), null, null, transfer.description(),
                clock);
        return transactionRepositoryPort.save(creditPending);
    }

    private Single<TransferStatus> completeTransfer(Transfer transfer, Transaction creditPending,
                                                      MovementOutcome outcome) {
        Transaction creditCompleted = creditPending.complete(outcome.resultingBalance(), clock);
        Transfer completed = transfer.completed(creditCompleted.id(), clock);
        return unitOfWorkPort.saveTransferAndTransaction(completed, creditCompleted)
                .flatMap(saved -> eventPublisherPort.publish(TransferCompleted.from(saved))
                        .andThen(Single.just(saved.status())));
    }

    private Single<TransferStatus> startCompensationFromRecovery(Transfer transfer, Transaction creditPending,
                                                                   MovementOutcome outcome) {
        Transaction creditFailed = creditPending.fail(outcome.failureReason(), clock);
        Transfer compensating = transfer.startCompensation(outcome.failureReason(), clock);
        return unitOfWorkPort.saveTransferAndTransaction(compensating, creditFailed).map(Transfer::status);
    }

    private Single<TransferStatus> retryReversal(Transfer transfer) {
        return transactionRepositoryPort.findByOperationId(transfer.operationId().forTransferOut())
                .flatMapSingle(debitCompleted -> accountMovementPort
                        .reverse(debitCompleted.operationId(), transfer.sourceAccountId())
                        .flatMap(outcome -> outcome.applied()
                                ? markCompensated(transfer, debitCompleted)
                                : markCompensationAttemptFailed(transfer, debitCompleted, outcome)))
                .switchIfEmpty(Single.just(transfer.status()));
    }

    private Single<TransferStatus> markCompensated(Transfer transfer, Transaction debitCompleted) {
        TransactionReversal reversal = new TransactionReversal(debitCompleted.operationId().forReversal(),
                ReversalOutcome.REVERSED, null);
        Transaction reversed = debitCompleted.markReversed(reversal, clock);
        Transfer compensated = transfer.compensated(clock);
        return unitOfWorkPort.saveTransferAndTransaction(compensated, reversed)
                .flatMap(saved -> eventPublisherPort.publish(TransferFailed.from(saved))
                        .andThen(Single.just(saved.status())));
    }

    private Single<TransferStatus> markCompensationAttemptFailed(Transfer transfer, Transaction debitCompleted,
                                                                   MovementOutcome outcome) {
        TransactionReversal failedReversal = new TransactionReversal(debitCompleted.operationId().forReversal(),
                ReversalOutcome.REVERSAL_FAILED, outcome.failureReason().code());
        Transaction annotated = debitCompleted.markReversalFailed(failedReversal, clock);
        Transfer afterAttempt = transfer.compensationAttemptFailed(clock);
        Transfer finalTransfer = afterAttempt.compensationAttempts() >= maxCompensationAttempts
                ? afterAttempt.compensationFailed(clock)
                : afterAttempt;
        Single<Transfer> saveChain = unitOfWorkPort.saveTransferAndTransaction(finalTransfer, annotated);
        return finalTransfer.status() == TransferStatus.COMPENSATION_FAILED
                ? saveChain.flatMap(saved -> eventPublisherPort.publish(TransferFailed.from(saved))
                        .andThen(Single.just(saved.status())))
                : saveChain.map(Transfer::status);
    }
}
