package com.bank.transaction.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.transaction.application.command.RecordExternalMovementCommand;
import com.bank.transaction.application.usecase.TestAdapters.RecordingEventPublisherPort;
import com.bank.transaction.domain.event.TransactionRegistered;
import com.bank.transaction.domain.exception.BusinessRuleViolationException;
import com.bank.transaction.domain.model.Money;
import com.bank.transaction.domain.model.OperationId;
import com.bank.transaction.domain.model.ProductRef;
import com.bank.transaction.domain.model.ProductType;
import com.bank.transaction.domain.model.Transaction;
import com.bank.transaction.domain.model.TransactionStatus;
import com.bank.transaction.domain.model.TransactionType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** Regla 12: credit-service ya aplicó el efecto, este caso de uso solo registra el historial. */
class RecordExternalMovementUseCaseImplTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-28T10:00:00Z"), ZoneOffset.UTC);
    private final InMemoryTransactionRepository repository = new InMemoryTransactionRepository();
    private final RecordingEventPublisherPort eventPublisherPort = new RecordingEventPublisherPort();
    private final RecordExternalMovementUseCaseImpl useCase =
            new RecordExternalMovementUseCaseImpl(repository, eventPublisherPort, clock);

    private RecordExternalMovementCommand command(String operationIdValue, BigDecimal amount) {
        return new RecordExternalMovementCommand(new OperationId(operationIdValue),
                new ProductRef("cred-1", ProductType.CREDIT), "cust-A", TransactionType.CREDIT_PAYMENT,
                Money.of(amount), Money.of(new BigDecimal("400.00")), "pago de credito");
    }

    @Test
    void recordsTheMovementAsAlreadyCompletedAndPublishesTransactionRegistered() {
        Transaction tx = useCase.execute(command("op-1", new BigDecimal("100.00"))).blockingGet();

        assertThat(tx.status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(eventPublisherPort.transactionEvents()).hasSize(1);
        assertThat(eventPublisherPort.transactionEvents().get(0)).isInstanceOf(TransactionRegistered.class);
    }

    @Test
    void repeatingTheSameOperationIdReplaysTheRecordWithoutSavingTwice() {
        RecordExternalMovementCommand command = command("op-1", new BigDecimal("100.00"));

        Transaction first = useCase.execute(command).blockingGet();
        Transaction second = useCase.execute(command).blockingGet();

        assertThat(second).isEqualTo(first);
        assertThat(repository.size()).isEqualTo(1);
        assertThat(eventPublisherPort.transactionEvents()).hasSize(1);
    }

    @Test
    void reusingAnOperationIdWithADifferentAmountIsRejected() {
        useCase.execute(command("op-1", new BigDecimal("100.00"))).test().assertComplete();

        useCase.execute(command("op-1", new BigDecimal("999.00"))).test()
                .assertError(error -> error instanceof BusinessRuleViolationException e
                        && "OPERATION_ID_REUSED".equals(e.getErrorCode()));
    }
}
