package com.bank.transaction.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.transaction.application.port.in.TransactionFilter;
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

/** customerId es obligatorio en esta consulta administrativa (ficha, sección 5); se valida
 *  en el propio caso de uso, no en el record TransactionFilter. */
class FindTransactionsUseCaseImplTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-28T10:00:00Z"), ZoneOffset.UTC);
    private final InMemoryTransactionRepository repository = new InMemoryTransactionRepository();
    private final FindTransactionsUseCaseImpl useCase = new FindTransactionsUseCaseImpl(repository);

    @Test
    void rejectsAQueryWithoutACustomerId() {
        useCase.execute(new TransactionFilter(null, null, null, null), new PageRequest(0, 20))
                .test().assertError(IllegalArgumentException.class);
    }

    @Test
    void returnsAPageOfTheCustomersTransactions() {
        Transaction tx = Transaction.pending(new OperationId("op-1"), new ProductRef("acc-1", ProductType.ACCOUNT),
                "cust-A", TransactionType.DEPOSIT, Money.of(new BigDecimal("50.00")), null, null, null, null, clock);
        repository.save(tx).blockingGet();

        PageView<Transaction> page = useCase
                .execute(new TransactionFilter("cust-A", null, null, null), new PageRequest(0, 20)).blockingGet();

        assertThat(page.items()).containsExactly(tx);
        assertThat(page.totalElements()).isEqualTo(1);
    }
}
