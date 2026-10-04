package com.bank.transaction.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.transaction.application.command.StartTransferCommand;
import com.bank.transaction.application.usecase.TestAdapters.FakeAccountMovementPort;
import com.bank.transaction.application.usecase.TestAdapters.PassthroughUnitOfWorkPort;
import com.bank.transaction.application.usecase.TestAdapters.RecordingEventPublisherPort;
import com.bank.transaction.application.usecase.TestAdapters.StubAccountLookupPort;
import com.bank.transaction.domain.event.TransferCompleted;
import com.bank.transaction.domain.event.TransferFailed;
import com.bank.transaction.domain.exception.AccountNotFoundException;
import com.bank.transaction.domain.exception.BusinessRuleViolationException;
import com.bank.transaction.domain.model.AccountSnapshot;
import com.bank.transaction.domain.model.AccountStatus;
import com.bank.transaction.domain.model.AccountType;
import com.bank.transaction.domain.model.FailureReason;
import com.bank.transaction.domain.model.Money;
import com.bank.transaction.domain.model.MovementOutcome;
import com.bank.transaction.domain.model.OperationId;
import com.bank.transaction.domain.model.Transaction;
import com.bank.transaction.domain.model.TransactionStatus;
import com.bank.transaction.domain.model.Transfer;
import com.bank.transaction.domain.model.TransferStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** Cubre la saga completa (ficha, sección 4.2 P2): débito, crédito y la compensación cuando
 *  el crédito falla, incluyendo el caso en que la propia reversa también falla y la
 *  transferencia se queda COMPENSATING a la espera de RecoverPendingOperationsUseCase. */
class StartTransferUseCaseImplTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-28T10:00:00Z"), ZoneOffset.UTC);
    private final InMemoryTransactionRepository transactionRepository = new InMemoryTransactionRepository();
    private final InMemoryTransferRepository transferRepository = new InMemoryTransferRepository();
    private final StubAccountLookupPort accountLookupPort = new StubAccountLookupPort()
            .with(new AccountSnapshot("acc-A", "cust-A", AccountType.SAVINGS, AccountStatus.ACTIVE))
            .with(new AccountSnapshot("acc-B", "cust-A", AccountType.SAVINGS, AccountStatus.ACTIVE))
            .with(new AccountSnapshot("acc-C", "cust-C", AccountType.SAVINGS, AccountStatus.ACTIVE));
    private final FakeAccountMovementPort accountMovementPort = new FakeAccountMovementPort();
    private final RecordingEventPublisherPort eventPublisherPort = new RecordingEventPublisherPort();
    private final StartTransferUseCaseImpl useCase = new StartTransferUseCaseImpl(transferRepository,
            accountLookupPort, accountMovementPort,
            new PassthroughUnitOfWorkPort(transferRepository, transactionRepository), eventPublisherPort, clock);

    private StartTransferCommand command(String operationIdValue, String source, String target) {
        return new StartTransferCommand(new OperationId(operationIdValue), source, target,
                Money.of(new BigDecimal("200.00")), "transferencia", "cust-A");
    }

    @Test
    void completesAnOwnTransferAndPublishesTransferCompleted() {
        Transfer result = useCase.execute(command("op-1", "acc-A", "acc-B")).blockingGet();

        assertThat(result.status()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(eventPublisherPort.transferEvents()).hasSize(1);
        assertThat(eventPublisherPort.transferEvents().get(0)).isInstanceOf(TransferCompleted.class);
        assertThat(accountMovementPort.appliedOperationIds()).containsExactly("op-1-OUT", "op-1-IN");
        Transaction debit = transactionRepository.findByOperationId(new OperationId("op-1-OUT")).blockingGet();
        Transaction credit = transactionRepository.findByOperationId(new OperationId("op-1-IN")).blockingGet();
        assertThat(debit.status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(credit.status()).isEqualTo(TransactionStatus.COMPLETED);
    }

    @Test
    void aRejectedDebitFailsTheTransferAndPublishesTransferFailed() {
        accountMovementPort.willApply("op-1-OUT",
                MovementOutcome.rejected(new OperationId("op-1-OUT"),
                        new FailureReason("INSUFFICIENT_FUNDS", "no alcanza")));

        useCase.execute(command("op-1", "acc-A", "acc-B")).test()
                .assertError(error -> error instanceof BusinessRuleViolationException e
                        && "INSUFFICIENT_FUNDS".equals(e.getErrorCode()));

        Transfer saved = transferRepository.findByOperationId(new OperationId("op-1")).blockingGet();
        assertThat(saved.status()).isEqualTo(TransferStatus.FAILED);
        assertThat(eventPublisherPort.transferEvents()).hasSize(1);
        assertThat(eventPublisherPort.transferEvents().get(0)).isInstanceOf(TransferFailed.class);
        assertThat(accountMovementPort.appliedOperationIds()).containsExactly("op-1-OUT"); // never tried the credit
    }

    @Test
    void aRejectedCreditCompensatesTheDebitAndEndsCompensated() {
        accountMovementPort.willApply("op-1-IN",
                MovementOutcome.rejected(new OperationId("op-1-IN"),
                        new FailureReason("ACCOUNT_INACTIVE", "destino inactivo")));

        useCase.execute(command("op-1", "acc-A", "acc-B")).test()
                .assertError(error -> error instanceof BusinessRuleViolationException e
                        && "ACCOUNT_INACTIVE".equals(e.getErrorCode()));

        Transfer saved = transferRepository.findByOperationId(new OperationId("op-1")).blockingGet();
        assertThat(saved.status()).isEqualTo(TransferStatus.COMPENSATED);
        assertThat(accountMovementPort.reversedOperationIds()).containsExactly("op-1-OUT-REV");
        Transaction debit = transactionRepository.findByOperationId(new OperationId("op-1-OUT")).blockingGet();
        assertThat(debit.status()).isEqualTo(TransactionStatus.REVERSED);
        assertThat(eventPublisherPort.transferEvents()).hasSize(1);
        TransferFailed event = (TransferFailed) eventPublisherPort.transferEvents().get(0);
        assertThat(event.status()).isEqualTo("COMPENSATED");
    }

    @Test
    void aRejectedCreditWithAFailedReversalStaysCompensatingWithoutPublishing() {
        accountMovementPort.willApply("op-1-IN",
                MovementOutcome.rejected(new OperationId("op-1-IN"),
                        new FailureReason("ACCOUNT_INACTIVE", "destino inactivo")));
        accountMovementPort.willReverse("op-1-OUT-REV",
                MovementOutcome.rejected(new OperationId("op-1-OUT-REV"),
                        new FailureReason("ACCOUNT_INACTIVE", "origen tambien inactivo")));

        useCase.execute(command("op-1", "acc-A", "acc-B")).test()
                .assertError(error -> error instanceof BusinessRuleViolationException e
                        && "ACCOUNT_INACTIVE".equals(e.getErrorCode()));

        Transfer saved = transferRepository.findByOperationId(new OperationId("op-1")).blockingGet();
        assertThat(saved.status()).isEqualTo(TransferStatus.COMPENSATING); // not COMPENSATION_FAILED yet
        assertThat(saved.compensationAttempts()).isEqualTo(1);
        // TransferFailed only fires on a terminal status (FAILED/COMPENSATED/COMPENSATION_FAILED).
        assertThat(eventPublisherPort.transferEvents()).isEmpty();
    }

    @Test
    void rejectsATransferBetweenTheSameAccount() {
        useCase.execute(command("op-1", "acc-A", "acc-A")).test()
                .assertError(error -> error instanceof BusinessRuleViolationException e
                        && "SAME_ACCOUNT".equals(e.getErrorCode()));
    }

    @Test
    void rejectsAnUnknownSourceOrTargetAccount() {
        useCase.execute(command("op-1", "acc-missing", "acc-B")).test().assertError(AccountNotFoundException.class);
        useCase.execute(command("op-2", "acc-A", "acc-missing")).test().assertError(AccountNotFoundException.class);
    }

    @Test
    void repeatingTheSameOperationIdReplaysTheResultWithoutMovingMoneyTwice() {
        StartTransferCommand command = command("op-1", "acc-A", "acc-B");

        Transfer first = useCase.execute(command).blockingGet();
        Transfer second = useCase.execute(command).blockingGet();

        assertThat(second).isEqualTo(first);
        assertThat(accountMovementPort.appliedOperationIds()).containsExactly("op-1-OUT", "op-1-IN"); // not repeated
        assertThat(eventPublisherPort.transferEvents()).hasSize(1);
    }

    @Test
    void reusingAnOperationIdWithADifferentTargetIsRejected() {
        useCase.execute(command("op-1", "acc-A", "acc-B")).test().assertComplete();

        useCase.execute(command("op-1", "acc-A", "acc-C")).test()
                .assertError(error -> error instanceof BusinessRuleViolationException e
                        && "OPERATION_ID_REUSED".equals(e.getErrorCode()));
    }
}
