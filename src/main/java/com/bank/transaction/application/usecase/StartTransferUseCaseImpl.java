package com.bank.transaction.application.usecase;

import com.bank.transaction.application.command.StartTransferCommand;
import com.bank.transaction.application.port.in.StartTransferUseCase;
import com.bank.transaction.application.port.out.AccountLookupPort;
import com.bank.transaction.application.port.out.AccountMovementPort;
import com.bank.transaction.application.port.out.TransactionEventPublisherPort;
import com.bank.transaction.application.port.out.TransferRepositoryPort;
import com.bank.transaction.application.port.out.UnitOfWorkPort;
import com.bank.transaction.domain.event.TransferCompleted;
import com.bank.transaction.domain.event.TransferFailed;
import com.bank.transaction.domain.exception.AccountNotFoundException;
import com.bank.transaction.domain.exception.BusinessRuleViolationException;
import com.bank.transaction.domain.model.AccountSnapshot;
import com.bank.transaction.domain.model.MovementOutcome;
import com.bank.transaction.domain.model.ProductRef;
import com.bank.transaction.domain.model.ProductType;
import com.bank.transaction.domain.model.ReversalOutcome;
import com.bank.transaction.domain.model.Transaction;
import com.bank.transaction.domain.model.TransactionReversal;
import com.bank.transaction.domain.model.TransactionType;
import com.bank.transaction.domain.model.Transfer;
import com.bank.transaction.domain.model.TransferKind;
import com.bank.transaction.domain.model.TransferStatus;
import com.bank.transaction.domain.service.TransferPolicy;
import io.reactivex.rxjava3.core.Single;
import java.time.Clock;
import java.time.LocalDate;

/**
 * Orquesta la saga en el propio request (P2; ficha sección 4.2 "P2 vs P3"): retira del
 * origen, deposita en destino y, si el depósito falla, revierte el retiro (reglas 8 y 10).
 * Si la reversa también falla, el Transfer queda COMPENSATING con un intento registrado —
 * es RecoverPendingOperationsUseCase quien sigue reintentando y quien decide cuándo darse
 * por vencido con COMPENSATION_FAILED (regla 9), no esta clase: un solo request no tiene
 * presupuesto para reintentar indefinidamente.
 */
public class StartTransferUseCaseImpl implements StartTransferUseCase {

    private final TransferRepositoryPort transferRepositoryPort;
    private final AccountLookupPort accountLookupPort;
    private final AccountMovementPort accountMovementPort;
    private final UnitOfWorkPort unitOfWorkPort;
    private final TransactionEventPublisherPort eventPublisherPort;
    private final Clock clock;

    public StartTransferUseCaseImpl(TransferRepositoryPort transferRepositoryPort,
            AccountLookupPort accountLookupPort, AccountMovementPort accountMovementPort,
            UnitOfWorkPort unitOfWorkPort, TransactionEventPublisherPort eventPublisherPort, Clock clock) {
        this.transferRepositoryPort = transferRepositoryPort;
        this.accountLookupPort = accountLookupPort;
        this.accountMovementPort = accountMovementPort;
        this.unitOfWorkPort = unitOfWorkPort;
        this.eventPublisherPort = eventPublisherPort;
        this.clock = clock;
    }

    @Override
    public Single<Transfer> execute(StartTransferCommand command) {
        return transferRepositoryPort.findByOperationId(command.operationId())
                .flatMapSingle(existing -> replay(existing, command))
                .switchIfEmpty(Single.defer(() -> startNew(command)));
    }

    private Single<Transfer> replay(Transfer existing, StartTransferCommand command) {
        if (!existing.matches(command.sourceAccountId(), command.targetAccountId(), command.amount())) {
            return Single.error(new BusinessRuleViolationException("OPERATION_ID_REUSED",
                    "operationId " + command.operationId().value() + " was already used for a different transfer"));
        }
        return Single.just(existing);
    }

    // --- arranque e iniciar el débito -------------------------------------------------------

    private Single<Transfer> startNew(StartTransferCommand command) {
        return accountLookupPort.findById(command.sourceAccountId())
                .switchIfEmpty(Single.error(new AccountNotFoundException(command.sourceAccountId())))
                .flatMap(source -> accountLookupPort.findById(command.targetAccountId())
                        .switchIfEmpty(Single.error(new AccountNotFoundException(command.targetAccountId())))
                        .flatMap(target -> openAndDebit(command, source, target)));
    }

    private Single<Transfer> openAndDebit(StartTransferCommand command, AccountSnapshot source,
                                           AccountSnapshot target) {
        TransferKind kind = TransferPolicy.evaluate(source, target, command.amount());
        Transfer transfer = Transfer.start(command.operationId(), command.sourceAccountId(),
                command.targetAccountId(), command.amount(), kind, command.description(), source.customerId(),
                target.customerId(), command.requestedBy(), clock);
        Transaction debitPending = Transaction.pending(command.operationId().forTransferOut(),
                new ProductRef(command.sourceAccountId(), ProductType.ACCOUNT), source.customerId(),
                TransactionType.TRANSFER_OUT, command.amount(), transfer.id(), null, null, command.description(),
                clock);
        return unitOfWorkPort.saveTransferAndTransaction(transfer, debitPending)
                .flatMap(savedTransfer -> debit(savedTransfer, debitPending, target));
    }

    private Single<Transfer> debit(Transfer transfer, Transaction debitPending, AccountSnapshot target) {
        LocalDate today = LocalDate.now(clock);
        return accountMovementPort.apply(debitPending.operationId(), transfer.sourceAccountId(),
                        TransactionType.TRANSFER_OUT, transfer.amount(), today)
                .flatMap(outcome -> outcome.applied()
                        ? onDebitApplied(transfer, debitPending, outcome, target)
                        : onDebitRejected(transfer, debitPending, outcome));
    }

    private Single<Transfer> onDebitRejected(Transfer transfer, Transaction debitPending,
                                              MovementOutcome debitOutcome) {
        Transaction debitFailed = debitPending.fail(debitOutcome.failureReason(), clock);
        Transfer failedTransfer = transfer.failed(debitOutcome.failureReason(), clock);
        return unitOfWorkPort.saveTransferAndTransaction(failedTransfer, debitFailed)
                .flatMap(saved -> eventPublisherPort.publish(TransferFailed.from(saved))
                        .andThen(Single.just(saved)));
    }

    // --- depositar en destino ---------------------------------------------------------------

    private Single<Transfer> onDebitApplied(Transfer transfer, Transaction debitPending,
                                             MovementOutcome debitOutcome, AccountSnapshot target) {
        Transaction debitCompleted = debitPending.complete(debitOutcome.resultingBalance(), clock);
        return unitOfWorkPort.saveTransferAndTransaction(transfer.sourceDebited(debitCompleted.id(), clock),
                        debitCompleted)
                .flatMap(sourceDebitedTransfer -> credit(sourceDebitedTransfer, debitCompleted, target));
    }

    private Single<Transfer> credit(Transfer transfer, Transaction debitCompleted, AccountSnapshot target) {
        Transaction creditPending = Transaction.pending(transfer.operationId().forTransferIn(),
                new ProductRef(transfer.targetAccountId(), ProductType.ACCOUNT), target.customerId(),
                TransactionType.TRANSFER_IN, transfer.amount(), transfer.id(), null, null, transfer.description(),
                clock);
        LocalDate today = LocalDate.now(clock);
        return unitOfWorkPort.saveTransferAndTransaction(transfer, creditPending)
                .flatMap(savedTransfer -> accountMovementPort.apply(creditPending.operationId(),
                                transfer.targetAccountId(), TransactionType.TRANSFER_IN, transfer.amount(), today)
                        .flatMap(outcome -> outcome.applied()
                                ? onCreditApplied(savedTransfer, creditPending, outcome)
                                : onCreditRejected(savedTransfer, creditPending, outcome, debitCompleted)));
    }

    private Single<Transfer> onCreditApplied(Transfer transfer, Transaction creditPending,
                                              MovementOutcome creditOutcome) {
        Transaction creditCompleted = creditPending.complete(creditOutcome.resultingBalance(), clock);
        Transfer completedTransfer = transfer.completed(creditCompleted.id(), clock);
        return unitOfWorkPort.saveTransferAndTransaction(completedTransfer, creditCompleted)
                .flatMap(saved -> eventPublisherPort.publish(TransferCompleted.from(saved))
                        .andThen(Single.just(saved)));
    }

    // --- crédito rechazado: compensar el débito ---------------------------------------------

    private Single<Transfer> onCreditRejected(Transfer transfer, Transaction creditPending,
                                               MovementOutcome creditOutcome, Transaction debitCompleted) {
        Transaction creditFailed = creditPending.fail(creditOutcome.failureReason(), clock);
        Transfer compensating = transfer.startCompensation(creditOutcome.failureReason(), clock);
        return unitOfWorkPort.saveTransferAndTransaction(compensating, creditFailed)
                .flatMap(saved -> reverseDebit(saved, debitCompleted))
                .flatMap(this::finishRejectedCredit);
    }

    private Single<Transfer> reverseDebit(Transfer compensating, Transaction debitCompleted) {
        return accountMovementPort.reverse(debitCompleted.operationId(), compensating.sourceAccountId())
                .flatMap(reversalOutcome -> reversalOutcome.applied()
                        ? markReversed(compensating, debitCompleted)
                        : markReversalAttemptFailed(compensating, debitCompleted, reversalOutcome));
    }

    private Single<Transfer> markReversed(Transfer compensating, Transaction debitCompleted) {
        TransactionReversal reversal = new TransactionReversal(debitCompleted.operationId().forReversal(),
                ReversalOutcome.REVERSED, null);
        Transaction reversed = debitCompleted.markReversed(reversal, clock);
        return unitOfWorkPort.saveTransferAndTransaction(compensating.compensated(clock), reversed);
    }

    private Single<Transfer> markReversalAttemptFailed(Transfer compensating, Transaction debitCompleted,
                                                         MovementOutcome reversalOutcome) {
        TransactionReversal failedReversal = new TransactionReversal(debitCompleted.operationId().forReversal(),
                ReversalOutcome.REVERSAL_FAILED, reversalOutcome.failureReason().code());
        Transaction annotated = debitCompleted.markReversalFailed(failedReversal, clock);
        return unitOfWorkPort.saveTransferAndTransaction(compensating.compensationAttemptFailed(clock), annotated);
    }

    /** TransferFailed solo se publica en un estado terminal (FAILED/COMPENSATED/
     *  COMPENSATION_FAILED, ficha sección 3.6) — si la reversa quedó COMPENSATING a la
     *  espera de un reintento, todavía no hay nada que anunciar. */
    /**
     * Devuelve la Transfer (no un error), igual que la repetición del mismo operationId: el
     * controller decide la respuesta por su estado. COMPENSATED → 422 {@code TransferRejected}
     * con {@code transferId}/{@code transferStatus}; COMPENSATING (la reversa falló y queda para la
     * recuperación) → 202. TransferFailed solo se publica en un estado terminal.
     */
    private Single<Transfer> finishRejectedCredit(Transfer saved) {
        return saved.status() == TransferStatus.COMPENSATING
                ? Single.just(saved)
                : eventPublisherPort.publish(TransferFailed.from(saved)).andThen(Single.just(saved));
    }
}
