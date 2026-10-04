package com.bank.transaction.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.transaction.application.port.in.ProductTransactionFilter;
import com.bank.transaction.application.view.PageRequest;
import com.bank.transaction.application.view.PageView;
import com.bank.transaction.domain.model.Money;
import com.bank.transaction.domain.model.OperationId;
import com.bank.transaction.domain.model.ProductRef;
import com.bank.transaction.domain.model.ProductType;
import com.bank.transaction.domain.model.Transaction;
import com.bank.transaction.domain.model.TransactionType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class FindProductTransactionsUseCaseImplTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-28T10:00:00Z"), ZoneOffset.UTC);
    private final InMemoryTransactionRepository repository = new InMemoryTransactionRepository();
    private final FindProductTransactionsUseCaseImpl useCase = new FindProductTransactionsUseCaseImpl(repository);

    @Test
    void returnsAnEmptyPageForAProductWithNoMovements() {
        PageView<Transaction> page = useCase.execute("acc-nobody", ProductTransactionFilter.all(),
                new PageRequest(0, 20)).blockingGet();

        assertThat(page.items()).isEmpty();
        assertThat(page.totalElements()).isZero();
    }

    @Test
    void returnsOnlyTheGivenProductsTransactions() {
        Transaction ofAccountOne = Transaction.pending(new OperationId("op-1"),
                new ProductRef("acc-1", ProductType.ACCOUNT), "cust-A", TransactionType.DEPOSIT,
                Money.of(new BigDecimal("50.00")), null, null, null, null, clock);
        Transaction ofAccountTwo = Transaction.pending(new OperationId("op-2"),
                new ProductRef("acc-2", ProductType.ACCOUNT), "cust-A", TransactionType.DEPOSIT,
                Money.of(new BigDecimal("50.00")), null, null, null, null, clock);
        repository.save(ofAccountOne).blockingGet();
        repository.save(ofAccountTwo).blockingGet();

        PageView<Transaction> page = useCase.execute("acc-1", ProductTransactionFilter.all(), new PageRequest(0, 20))
                .blockingGet();

        assertThat(page.items()).containsExactly(ofAccountOne);
    }
}
