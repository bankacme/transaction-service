package com.bank.transaction.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.transaction.application.command.UpdateTransactionDescriptionCommand;
import com.bank.transaction.domain.exception.BusinessRuleViolationException;
import com.bank.transaction.domain.exception.TransactionNotFoundException;
import com.bank.transaction.domain.model.FailureReason;
import com.bank.transaction.domain.model.Money;
import com.bank.transaction.domain.model.OperationId;
import com.bank.transaction.domain.model.ProductRef;
import com.bank.transaction.domain.model.ProductType;
import com.bank.transaction.domain.model.Transaction;
import com.bank.transaction.domain.model.TransactionId;
import com.bank.transaction.domain.model.TransactionType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class UpdateTransactionDescriptionUseCaseImplTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-28T10:00:00Z"), ZoneOffset.UTC);
    private final InMemoryTransactionRepository repository = new InMemoryTransactionRepository();
    private final UpdateTransactionDescriptionUseCaseImpl useCase =
            new UpdateTransactionDescriptionUseCaseImpl(repository, clock);

    @Test
    void updatesOnlyTheDescriptionAndPersistsIt() {
        Transaction tx = Transaction.pending(new OperationId("op-1"), new ProductRef("acc-1", ProductType.ACCOUNT),
                "cust-A", TransactionType.DEPOSIT, Money.of(new BigDecimal("50.00")), null, null, null, "original",
                clock);
        repository.save(tx).blockingGet();

        Transaction updated = useCase.execute(new UpdateTransactionDescriptionCommand(tx.id(), "corregida"))
                .blockingGet();

        assertThat(updated.description()).isEqualTo("corregida");
        assertThat(repository.findById(tx.id()).blockingGet().description()).isEqualTo("corregida");
    }

    @Test
    void rejectsAnUnknownTransactionId() {
        useCase.execute(new UpdateTransactionDescriptionCommand(TransactionId.newId(), "nueva"))
                .test().assertError(TransactionNotFoundException.class);
    }

    @Test
    void rejectsEditingADiscardedTransaction() {
        Transaction discarded = Transaction.pending(new OperationId("op-1"),
                new ProductRef("acc-1", ProductType.ACCOUNT), "cust-A", TransactionType.DEPOSIT,
                Money.of(new BigDecimal("50.00")), null, null, null, null, clock)
                .fail(new FailureReason("INSUFFICIENT_FUNDS", "no alcanza"), clock)
                .discard(clock);
        repository.save(discarded).blockingGet();

        useCase.execute(new UpdateTransactionDescriptionCommand(discarded.id(), "nueva")).test()
                .assertError(error -> error instanceof BusinessRuleViolationException e
                        && "TRANSACTION_DISCARDED".equals(e.getErrorCode()));
    }
}
