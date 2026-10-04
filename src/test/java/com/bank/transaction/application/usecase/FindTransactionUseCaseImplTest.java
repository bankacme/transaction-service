package com.bank.transaction.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.transaction.domain.exception.TransactionNotFoundException;
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

class FindTransactionUseCaseImplTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-28T10:00:00Z"), ZoneOffset.UTC);
    private final InMemoryTransactionRepository repository = new InMemoryTransactionRepository();
    private final FindTransactionUseCaseImpl useCase = new FindTransactionUseCaseImpl(repository);

    @Test
    void returnsTheTransactionWhenItExists() {
        Transaction tx = Transaction.pending(new OperationId("op-1"), new ProductRef("acc-1", ProductType.ACCOUNT),
                "cust-A", TransactionType.DEPOSIT, Money.of(new BigDecimal("50.00")), null, null, null, null, clock);
        repository.save(tx).blockingGet();

        Transaction found = useCase.execute(tx.id()).blockingGet();

        assertThat(found).isEqualTo(tx);
    }

    @Test
    void rejectsAnUnknownTransactionId() {
        useCase.execute(TransactionId.newId()).test().assertError(TransactionNotFoundException.class);
    }
}
