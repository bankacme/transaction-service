package com.bank.transaction.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.transaction.domain.exception.BusinessRuleViolationException;
import com.bank.transaction.domain.exception.TransactionNotFoundException;
import com.bank.transaction.domain.model.FailureReason;
import com.bank.transaction.domain.model.Money;
import com.bank.transaction.domain.model.OperationId;
import com.bank.transaction.domain.model.ProductRef;
import com.bank.transaction.domain.model.ProductType;
import com.bank.transaction.domain.model.Transaction;
import com.bank.transaction.domain.model.TransactionId;
import com.bank.transaction.domain.model.TransactionStatus;
import com.bank.transaction.domain.model.TransactionType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class DiscardTransactionUseCaseImplTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-28T10:00:00Z"), ZoneOffset.UTC);
    private final InMemoryTransactionRepository repository = new InMemoryTransactionRepository();
    private final DiscardTransactionUseCaseImpl useCase = new DiscardTransactionUseCaseImpl(repository, clock);

    @Test
    void discardsAFailedTransaction() {
        Transaction failed = Transaction.pending(new OperationId("op-1"), new ProductRef("acc-1", ProductType.ACCOUNT),
                "cust-A", TransactionType.DEPOSIT, Money.of(new BigDecimal("50.00")), null, null, null, null, clock)
                .fail(new FailureReason("INSUFFICIENT_FUNDS", "no alcanza"), clock);
        repository.save(failed).blockingGet();

        useCase.execute(failed.id()).test().assertComplete();

        assertThat(repository.findById(failed.id()).blockingGet().status()).isEqualTo(TransactionStatus.DISCARDED);
    }

    @Test
    void discardingTwiceIsIdempotent() {
        Transaction failed = Transaction.pending(new OperationId("op-1"), new ProductRef("acc-1", ProductType.ACCOUNT),
                "cust-A", TransactionType.DEPOSIT, Money.of(new BigDecimal("50.00")), null, null, null, null, clock)
                .fail(new FailureReason("INSUFFICIENT_FUNDS", "no alcanza"), clock);
        repository.save(failed).blockingGet();
        useCase.execute(failed.id()).test().assertComplete();

        useCase.execute(failed.id()).test().assertComplete();

        assertThat(repository.findById(failed.id()).blockingGet().status()).isEqualTo(TransactionStatus.DISCARDED);
    }

    @Test
    void rejectsAnUnknownTransactionId() {
        useCase.execute(TransactionId.newId()).test().assertError(TransactionNotFoundException.class);
    }

    @Test
    void rejectsDiscardingATransactionThatIsNotFailed() {
        Transaction completed = Transaction.record(new OperationId("op-1"), new ProductRef("cred-1",
                ProductType.CREDIT), "cust-A", TransactionType.CREDIT_PAYMENT, Money.of(new BigDecimal("50.00")),
                Money.of(new BigDecimal("400.00")), null, clock);
        repository.save(completed).blockingGet();

        useCase.execute(completed.id()).test()
                .assertError(error -> error instanceof BusinessRuleViolationException e
                        && "NOT_DISCARDABLE".equals(e.getErrorCode()));
    }
}
