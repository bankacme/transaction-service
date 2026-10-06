package com.bank.transaction.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.transaction.application.usecase.TestAdapters.FakeAccountMovementPort;
import com.bank.transaction.application.usecase.TestAdapters.PassthroughUnitOfWorkPort;
import com.bank.transaction.application.usecase.TestAdapters.RecordingEventPublisherPort;
import com.bank.transaction.application.view.RecoveryResult;
import com.bank.transaction.domain.event.TransferCompleted;
import com.bank.transaction.domain.event.TransferFailed;
import com.bank.transaction.domain.model.FailureReason;
import com.bank.transaction.domain.model.Money;
import com.bank.transaction.domain.model.MovementOutcome;
import com.bank.transaction.domain.model.OperationId;
import com.bank.transaction.domain.model.ProductRef;
import com.bank.transaction.domain.model.ProductType;
import com.bank.transaction.domain.model.Transaction;
import com.bank.transaction.domain.model.TransactionStatus;
import com.bank.transaction.domain.model.TransactionType;
import com.bank.transaction.domain.model.Transfer;
import com.bank.transaction.domain.model.TransferKind;
import com.bank.transaction.domain.model.TransferStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** Regla 14. "Viejo" en estos tests significa: construido con un Clock fijado más atrás que
 *  el {@code now} del propio caso de uso, para que caiga del lado correcto del umbral sin
 *  depender del reloj real de la máquina. maxCompensationAttempts se deja en 2 para poder
 *  probar "se agotan los reintentos" sin escribir un bucle de 10 iteraciones. */
class RecoverPendingOperationsUseCaseImplTest {

    private static final int DEFAULT_OLDER_THAN_MINUTES = 10;
    private static final int MAX_COMPENSATION_ATTEMPTS = 2;

    private final Instant now = Instant.parse("2026-09-28T10:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final Clock oldClock = Clock.fixed(now.minus(Duration.ofMinutes(20)), ZoneOffset.UTC);
    private final Clock recentClock = Clock.fixed(now.minus(Duration.ofMinutes(2)), ZoneOffset.UTC);

    private final InMemoryTransactionRepository transactionRepository = new InMemoryTransactionRepository();
    private final InMemoryTransferRepository transferRepository = new InMemoryTransferRepository();
    private final FakeAccountMovementPort accountMovementPort = new FakeAccountMovementPort();
    private final RecordingEventPublisherPort eventPublisherPort = new RecordingEventPublisherPort();
    private final RecoverPendingOperationsUseCaseImpl useCase = new RecoverPendingOperationsUseCaseImpl(
            transactionRepository, transferRepository, accountMovementPort,
            new PassthroughUnitOfWorkPort(transferRepository, transactionRepository), eventPublisherPort,
            DEFAULT_OLDER_THAN_MINUTES, MAX_COMPENSATION_ATTEMPTS, clock);

    private final ProductRef accountProduct = new ProductRef("acc-1", ProductType.ACCOUNT);
    private final Money amount = Money.of(new BigDecimal("100.00"));

    // --- movimientos simples (DEPOSIT/WITHDRAWAL) ------------------------------------------

    @Test
    void recoversAPendingDepositOlderThanTheThresholdAndCompletesIt() {
        Transaction pending = Transaction.pending(new OperationId("op-1"), accountProduct, "cust-A",
                TransactionType.DEPOSIT, amount, null, null, null, null, oldClock);
        transactionRepository.save(pending).blockingGet();

        RecoveryResult result = useCase.execute(null).blockingGet();

        assertThat(result.transactionsRetried()).isEqualTo(1);
        assertThat(result.transactionsCompleted()).isEqualTo(1);
        assertThat(result.transactionsFailed()).isEqualTo(0);
        Transaction saved = transactionRepository.findById(pending.id()).blockingGet();
        assertThat(saved.status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(eventPublisherPort.transactionEvents()).hasSize(1);
    }

    @Test
    void recoversAPendingWithdrawalThatStillFailsAndCountsItAsFailed() {
        Transaction pending = Transaction.pending(new OperationId("op-1"), accountProduct, "cust-A",
                TransactionType.WITHDRAWAL, amount, null, null, null, null, oldClock);
        transactionRepository.save(pending).blockingGet();
        accountMovementPort.willApply("op-1",
                MovementOutcome.rejected(new OperationId("op-1"),
                        new FailureReason("INSUFFICIENT_FUNDS", "no alcanza")));

        RecoveryResult result = useCase.execute(null).blockingGet();

        assertThat(result.transactionsRetried()).isEqualTo(1);
        assertThat(result.transactionsCompleted()).isEqualTo(0);
        assertThat(result.transactionsFailed()).isEqualTo(1);
        Transaction saved = transactionRepository.findById(pending.id()).blockingGet();
        assertThat(saved.status()).isEqualTo(TransactionStatus.FAILED);
        assertThat(eventPublisherPort.transactionEvents()).hasSize(1);
    }

    @Test
    void ignoresAPendingTransactionYoungerThanTheThreshold() {
        Transaction recent = Transaction.pending(new OperationId("op-1"), accountProduct, "cust-A",
                TransactionType.DEPOSIT, amount, null, null, null, null, recentClock);
        transactionRepository.save(recent).blockingGet();

        RecoveryResult result = useCase.execute(null).blockingGet();

        assertThat(result.transactionsRetried()).isEqualTo(0);
        assertThat(accountMovementPort.appliedOperationIds()).isEmpty();
        assertThat(transactionRepository.findById(recent.id()).blockingGet().status())
                .isEqualTo(TransactionStatus.PENDING);
    }

    @Test
    void anInfrastructureErrorOnOneTransactionDoesNotStopTheRestOfTheBatch() {
        Transaction erroring = Transaction.pending(new OperationId("op-boom"), accountProduct, "cust-A",
                TransactionType.DEPOSIT, amount, null, null, null, null, oldClock);
        Transaction healthy = Transaction.pending(new OperationId("op-ok"), accountProduct, "cust-A",
                TransactionType.DEPOSIT, amount, null, null, null, null, oldClock);
        transactionRepository.save(erroring).blockingGet();
        transactionRepository.save(healthy).blockingGet();
        accountMovementPort.willApplyError("op-boom", new RuntimeException("connection reset"));

        RecoveryResult result = useCase.execute(null).blockingGet();

        assertThat(result.transactionsRetried()).isEqualTo(2);
        assertThat(result.transactionsCompleted()).isEqualTo(1);
        assertThat(result.transactionsFailed()).isEqualTo(1);
        // left untouched (still PENDING), not marked FAILED, so the next pass retries it again
        assertThat(transactionRepository.findById(erroring.id()).blockingGet().status())
                .isEqualTo(TransactionStatus.PENDING);
        assertThat(transactionRepository.findById(healthy.id()).blockingGet().status())
                .isEqualTo(TransactionStatus.COMPLETED);
        assertThat(eventPublisherPort.transactionEvents()).hasSize(1);
    }

    @Test
    void anInfrastructureErrorOnOneTransferDoesNotStopTheRestOfTheBatch() {
        Transfer erroringTransfer = Transfer.start(new OperationId("op-boom"), "acc-A", "acc-B", amount,
                TransferKind.OWN, "transferencia", "cust-A", "cust-A", "cust-A", oldClock);
        Transaction boomDebitCompleted = Transaction.pending(erroringTransfer.operationId().forTransferOut(),
                accountProduct, "cust-A", TransactionType.TRANSFER_OUT, amount, erroringTransfer.id(), null, null,
                null, oldClock).complete(Money.of(new BigDecimal("500.00")), oldClock);
        Transaction boomCreditPending = Transaction.pending(erroringTransfer.operationId().forTransferIn(),
                new ProductRef("acc-B", ProductType.ACCOUNT), "cust-A", TransactionType.TRANSFER_IN, amount,
                erroringTransfer.id(), null, null, null, oldClock);
        erroringTransfer = erroringTransfer.sourceDebited(boomDebitCompleted.id(), oldClock);
        transactionRepository.save(boomDebitCompleted).blockingGet();
        transactionRepository.save(boomCreditPending).blockingGet();
        transferRepository.save(erroringTransfer).blockingGet();
        accountMovementPort.willApplyError("op-boom-IN", new RuntimeException("connection reset"));

        Transfer healthyTransfer = buildCompensatingTransfer(0); // its own op-1, resolves normally

        RecoveryResult result = useCase.execute(null).blockingGet();

        assertThat(result.transfersRetried()).isEqualTo(2);
        assertThat(result.transfersCompleted()).isEqualTo(1); // the healthy one compensates
        assertThat(result.transfersFailed()).isEqualTo(0);
        // left untouched (still SOURCE_DEBITED), not advanced, so the next pass retries it again
        Transfer savedErroring = transferRepository.findById(erroringTransfer.id()).blockingGet();
        assertThat(savedErroring.status()).isEqualTo(TransferStatus.SOURCE_DEBITED);
        Transfer savedHealthy = transferRepository.findById(healthyTransfer.id()).blockingGet();
        assertThat(savedHealthy.status()).isEqualTo(TransferStatus.COMPENSATED);
        assertThat(eventPublisherPort.transferEvents()).hasSize(1);
    }

    @Test
    void ignoresTransferLegsAsStandaloneTransactions() {
        Transaction pendingLeg = Transaction.pending(new OperationId("op-1-OUT"), accountProduct, "cust-A",
                TransactionType.TRANSFER_OUT, amount, null, null, null, null, oldClock);
        transactionRepository.save(pendingLeg).blockingGet();

        RecoveryResult result = useCase.execute(null).blockingGet();

        assertThat(result.transactionsRetried()).isEqualTo(0);
        assertThat(accountMovementPort.appliedOperationIds()).isEmpty();
    }

    // --- transferencia detenida en STARTED (debito) ------------------------------------------

    @Test
    void recoversATransferStuckAtStartedAndCompletesTheDebit() {
        Transfer transfer = Transfer.start(new OperationId("op-1"), "acc-A", "acc-B", amount, TransferKind.OWN,
                "transferencia", "cust-A", "cust-A", "cust-A", oldClock);
        Transaction debitPending = Transaction.pending(transfer.operationId().forTransferOut(), accountProduct,
                "cust-A", TransactionType.TRANSFER_OUT, amount, transfer.id(), null, null, null, oldClock);
        transferRepository.save(transfer).blockingGet();
        transactionRepository.save(debitPending).blockingGet();

        RecoveryResult result = useCase.execute(null).blockingGet();

        assertThat(result.transfersRetried()).isEqualTo(1);
        assertThat(result.transfersCompleted()).isEqualTo(0); // still in progress (now SOURCE_DEBITED)
        assertThat(result.transfersFailed()).isEqualTo(0);
        Transfer saved = transferRepository.findById(transfer.id()).blockingGet();
        assertThat(saved.status()).isEqualTo(TransferStatus.SOURCE_DEBITED);
        Transaction savedDebit = transactionRepository.findByOperationId(new OperationId("op-1-OUT")).blockingGet();
        assertThat(savedDebit.status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(eventPublisherPort.transferEvents()).isEmpty(); // not terminal yet
    }

    @Test
    void recoversATransferStuckAtStartedWhoseDebitIsRejectedAndFailsOutright() {
        Transfer transfer = Transfer.start(new OperationId("op-1"), "acc-A", "acc-B", amount, TransferKind.OWN,
                "transferencia", "cust-A", "cust-A", "cust-A", oldClock);
        Transaction debitPending = Transaction.pending(transfer.operationId().forTransferOut(), accountProduct,
                "cust-A", TransactionType.TRANSFER_OUT, amount, transfer.id(), null, null, null, oldClock);
        transferRepository.save(transfer).blockingGet();
        transactionRepository.save(debitPending).blockingGet();
        accountMovementPort.willApply("op-1-OUT", MovementOutcome.rejected(new OperationId("op-1-OUT"),
                new FailureReason("ACCOUNT_INACTIVE", "origen inactivo")));

        RecoveryResult result = useCase.execute(null).blockingGet();

        assertThat(result.transfersFailed()).isEqualTo(1); // FAILED counts as failed, same as COMPENSATION_FAILED
        Transfer saved = transferRepository.findById(transfer.id()).blockingGet();
        assertThat(saved.status()).isEqualTo(TransferStatus.FAILED);
        Transaction savedDebit = transactionRepository.findByOperationId(new OperationId("op-1-OUT")).blockingGet();
        assertThat(savedDebit.status()).isEqualTo(TransactionStatus.FAILED);
        assertThat(eventPublisherPort.transferEvents()).hasSize(1);
        assertThat(((TransferFailed) eventPublisherPort.transferEvents().get(0)).status()).isEqualTo("FAILED");
    }

    @Test
    void recoversATransferStuckAtStartedWithTheDebitLegMissingEntirelyByCreatingItFirst() {
        // Defensive case (see RecoverPendingOperationsUseCaseImpl's own Javadoc): the <op>-OUT
        // Transaction is missing outright, not just still PENDING.
        Transfer transfer = Transfer.start(new OperationId("op-1"), "acc-A", "acc-B", amount, TransferKind.OWN,
                "transferencia", "cust-A", "cust-A", "cust-A", oldClock);
        transferRepository.save(transfer).blockingGet();

        RecoveryResult result = useCase.execute(null).blockingGet();

        assertThat(result.transfersRetried()).isEqualTo(1);
        Transfer saved = transferRepository.findById(transfer.id()).blockingGet();
        assertThat(saved.status()).isEqualTo(TransferStatus.SOURCE_DEBITED);
        Transaction createdDebit = transactionRepository.findByOperationId(new OperationId("op-1-OUT"))
                .blockingGet();
        assertThat(createdDebit.status()).isEqualTo(TransactionStatus.COMPLETED);
    }

    // --- transferencias detenidas -----------------------------------------------------------

    @Test
    void recoversATransferStuckAtSourceDebitedAndCompletesTheCredit() {
        Transfer transfer = Transfer.start(new OperationId("op-1"), "acc-A", "acc-B", amount,
                TransferKind.OWN, "transferencia", "cust-A", "cust-A", "cust-A",
                oldClock);
        Transaction debitCompleted = Transaction.pending(transfer.operationId().forTransferOut(), accountProduct,
                "cust-A", TransactionType.TRANSFER_OUT, amount, transfer.id(), null, null, null, oldClock)
                .complete(Money.of(new BigDecimal("500.00")), oldClock);
        Transaction creditPending = Transaction.pending(transfer.operationId().forTransferIn(),
                new ProductRef("acc-B", ProductType.ACCOUNT), "cust-A", TransactionType.TRANSFER_IN, amount,
                transfer.id(), null, null, null, oldClock);
        transfer = transfer.sourceDebited(debitCompleted.id(), oldClock);
        transactionRepository.save(debitCompleted).blockingGet();
        transactionRepository.save(creditPending).blockingGet();
        transferRepository.save(transfer).blockingGet();

        RecoveryResult result = useCase.execute(null).blockingGet();

        assertThat(result.transfersRetried()).isEqualTo(1);
        assertThat(result.transfersCompleted()).isEqualTo(1);
        assertThat(result.transfersFailed()).isEqualTo(0);
        Transfer saved = transferRepository.findById(transfer.id()).blockingGet();
        assertThat(saved.status()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(eventPublisherPort.transferEvents()).hasSize(1);
        assertThat(eventPublisherPort.transferEvents().get(0)).isInstanceOf(TransferCompleted.class);
    }

    @Test
    void recoversATransferStuckAtSourceDebitedWithTheCreditLegMissingByCreatingItFirst() {
        // Caída entre las dos escrituras de StartTransferUseCaseImpl: SOURCE_DEBITED guardado,
        // pero la pata <op>-IN nunca llegó a guardarse.
        Transfer transfer = Transfer.start(new OperationId("op-1"), "acc-A", "acc-B", amount,
                TransferKind.THIRD_PARTY, "transferencia", "cust-A", "cust-B", "cust-A", oldClock);
        Transaction debitCompleted = Transaction.pending(transfer.operationId().forTransferOut(), accountProduct,
                "cust-A", TransactionType.TRANSFER_OUT, amount, transfer.id(), null, null, null, oldClock)
                .complete(Money.of(new BigDecimal("500.00")), oldClock);
        transfer = transfer.sourceDebited(debitCompleted.id(), oldClock);
        transactionRepository.save(debitCompleted).blockingGet();
        transferRepository.save(transfer).blockingGet();

        RecoveryResult result = useCase.execute(null).blockingGet();

        assertThat(result.transfersCompleted()).isEqualTo(1);
        assertThat(transferRepository.findById(transfer.id()).blockingGet().status())
                .isEqualTo(TransferStatus.COMPLETED);
        Transaction credit = transactionRepository.findByOperationId(new OperationId("op-1-IN")).blockingGet();
        assertThat(credit.status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(credit.customerId()).isEqualTo("cust-B");
        assertThat(credit.product().productId()).isEqualTo("acc-B");
    }

    @Test
    void recoversATransferStuckAtSourceDebitedWhoseCreditStillFailsStartsCompensation() {
        Transfer transfer = Transfer.start(new OperationId("op-1"), "acc-A", "acc-B", amount,
                TransferKind.OWN, "transferencia", "cust-A", "cust-A", "cust-A",
                oldClock);
        Transaction debitCompleted = Transaction.pending(transfer.operationId().forTransferOut(), accountProduct,
                "cust-A", TransactionType.TRANSFER_OUT, amount, transfer.id(), null, null, null, oldClock)
                .complete(Money.of(new BigDecimal("500.00")), oldClock);
        Transaction creditPending = Transaction.pending(transfer.operationId().forTransferIn(),
                new ProductRef("acc-B", ProductType.ACCOUNT), "cust-A", TransactionType.TRANSFER_IN, amount,
                transfer.id(), null, null, null, oldClock);
        transfer = transfer.sourceDebited(debitCompleted.id(), oldClock);
        transactionRepository.save(debitCompleted).blockingGet();
        transactionRepository.save(creditPending).blockingGet();
        transferRepository.save(transfer).blockingGet();
        accountMovementPort.willApply("op-1-IN", MovementOutcome.rejected(new OperationId("op-1-IN"),
                new FailureReason("ACCOUNT_INACTIVE", "destino inactivo")));

        RecoveryResult result = useCase.execute(null).blockingGet();

        assertThat(result.transfersRetried()).isEqualTo(1);
        assertThat(result.transfersCompleted()).isEqualTo(0); // still in progress, not resolved yet
        assertThat(result.transfersFailed()).isEqualTo(0);
        Transfer saved = transferRepository.findById(transfer.id()).blockingGet();
        assertThat(saved.status()).isEqualTo(TransferStatus.COMPENSATING);
        assertThat(eventPublisherPort.transferEvents()).isEmpty(); // not terminal yet
    }

    @Test
    void recoversATransferStuckCompensatingAndCompensatesSuccessfully() {
        Transfer transfer = buildCompensatingTransfer(0);

        RecoveryResult result = useCase.execute(null).blockingGet();

        assertThat(result.transfersRetried()).isEqualTo(1);
        assertThat(result.transfersCompleted()).isEqualTo(1);
        Transfer saved = transferRepository.findById(transfer.id()).blockingGet();
        assertThat(saved.status()).isEqualTo(TransferStatus.COMPENSATED);
        Transaction debit = transactionRepository.findByOperationId(new OperationId("op-1-OUT")).blockingGet();
        assertThat(debit.status()).isEqualTo(TransactionStatus.REVERSED);
        assertThat(eventPublisherPort.transferEvents()).hasSize(1);
        TransferFailed event = (TransferFailed) eventPublisherPort.transferEvents().get(0);
        assertThat(event.status()).isEqualTo("COMPENSATED");
    }

    @Test
    void aFirstFailedReversalAttemptStaysCompensatingWithoutGivingUp() {
        Transfer transfer = buildCompensatingTransfer(0);
        accountMovementPort.willReverse("op-1-OUT", MovementOutcome.rejected(new OperationId("op-1-OUT"),
                new FailureReason("ACCOUNT_INACTIVE", "origen tambien inactivo")));

        RecoveryResult result = useCase.execute(null).blockingGet();

        assertThat(result.transfersFailed()).isEqualTo(0);
        Transfer saved = transferRepository.findById(transfer.id()).blockingGet();
        assertThat(saved.status()).isEqualTo(TransferStatus.COMPENSATING);
        assertThat(saved.compensationAttempts()).isEqualTo(1);
        assertThat(eventPublisherPort.transferEvents()).isEmpty();
    }

    @Test
    void givesUpAfterExhaustingCompensationAttemptsAndPublishesTransferFailed() {
        // Ya tiene un intento fallido registrado (compensationAttempts = 1); este reintento es
        // el segundo, que alcanza MAX_COMPENSATION_ATTEMPTS = 2 y da por terminada la saga.
        Transfer transfer = buildCompensatingTransfer(1);
        accountMovementPort.willReverse("op-1-OUT", MovementOutcome.rejected(new OperationId("op-1-OUT"),
                new FailureReason("ACCOUNT_INACTIVE", "origen tambien inactivo")));

        RecoveryResult result = useCase.execute(null).blockingGet();

        assertThat(result.transfersFailed()).isEqualTo(1);
        assertThat(result.transfersCompleted()).isEqualTo(0);
        Transfer saved = transferRepository.findById(transfer.id()).blockingGet();
        assertThat(saved.status()).isEqualTo(TransferStatus.COMPENSATION_FAILED);
        assertThat(saved.compensationAttempts()).isEqualTo(2);
        assertThat(eventPublisherPort.transferEvents()).hasSize(1);
        assertThat(((TransferFailed) eventPublisherPort.transferEvents().get(0)).status())
                .isEqualTo("COMPENSATION_FAILED");
    }

    @Test
    void combinesTransactionAndTransferCountsIntoOneRecoveryResult() {
        Transaction completingDeposit = Transaction.pending(new OperationId("op-dep"), accountProduct, "cust-A",
                TransactionType.DEPOSIT, amount, null, null, null, null, oldClock);
        Transaction failingWithdrawal = Transaction.pending(new OperationId("op-wd"), accountProduct, "cust-A",
                TransactionType.WITHDRAWAL, amount, null, null, null, null, oldClock);
        transactionRepository.save(completingDeposit).blockingGet();
        transactionRepository.save(failingWithdrawal).blockingGet();
        accountMovementPort.willApply("op-wd",
                MovementOutcome.rejected(new OperationId("op-wd"), new FailureReason("X", "x")));
        buildCompensatingTransfer(0); // one transfer that successfully compensates

        RecoveryResult result = useCase.execute(null).blockingGet();

        assertThat(result).isEqualTo(new RecoveryResult(2, 1, 1, 1, 1, 0));
    }

    /** Transfer ya SOURCE_DEBITED y en COMPENSATING, con el Transaction de débito COMPLETED
     *  listo para ser revertido — exactamente el estado en que StartTransferUseCaseImpl deja
     *  una transferencia cuando el crédito fue rechazado. */
    private Transfer buildCompensatingTransfer(int priorCompensationAttempts) {
        Transfer transfer = Transfer.start(new OperationId("op-1"), "acc-A", "acc-B", amount,
                TransferKind.OWN, "transferencia", "cust-A", "cust-A", "cust-A",
                oldClock);
        Transaction debitCompleted = Transaction.pending(transfer.operationId().forTransferOut(), accountProduct,
                "cust-A", TransactionType.TRANSFER_OUT, amount, transfer.id(), null, null, null, oldClock)
                .complete(Money.of(new BigDecimal("500.00")), oldClock);
        transfer = transfer.sourceDebited(debitCompleted.id(), oldClock)
                .startCompensation(new FailureReason("ACCOUNT_INACTIVE", "destino inactivo"), oldClock);
        for (int i = 0; i < priorCompensationAttempts; i++) {
            transfer = transfer.compensationAttemptFailed(oldClock);
        }
        transactionRepository.save(debitCompleted).blockingGet();
        transferRepository.save(transfer).blockingGet();
        return transfer;
    }
}
