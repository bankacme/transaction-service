package com.bank.transaction.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.transaction.application.port.in.ProductTransactionFilter;
import com.bank.transaction.application.port.in.TransactionFilter;
import com.bank.transaction.application.view.PageRequest;
import com.bank.transaction.domain.model.DateRange;
import com.bank.transaction.domain.model.OperationId;
import com.bank.transaction.domain.model.Transaction;
import com.bank.transaction.domain.model.TransactionStatus;
import com.bank.transaction.domain.model.TransactionType;
import com.bank.transaction.infrastructure.fixture.TransactionFixtures;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The adapter-level behaviors {@link TransactionPersistenceAdapter}'s own javadoc promises:
 * the four-way pagination dispatch for both {@code findByProduct} and {@code findAll}, and the
 * {@code uk_tx_operation_id} race recovery. Not runnable in this sandbox (no Mongo) — see R4's
 * delivery notes.
 */
@SpringBootTest
class TransactionPersistenceAdapterTest {

    @Autowired
    private TransactionPersistenceAdapter adapter;

    @Autowired
    private TransactionMongoRepository repository;

    @BeforeEach
    @AfterEach
    void cleanCollection() {
        repository.deleteAll().blockingAwait();
    }

    @Test
    void findByProductWithNoFiltersExcludesDiscardedByDefault() {
        String productId = "prod-" + UUID.randomUUID();
        adapter.save(TransactionFixtures.pendingDeposit("op-1", productId, "cust-A", "100.00")).blockingGet();
        Transaction discarded = TransactionFixtures.failedWithdrawal("op-2", productId, "cust-A", "50.00", "X", "x")
                .discard(TransactionFixtures.CLOCK);
        adapter.save(discarded).blockingGet();

        var page = adapter.findByProduct(productId, ProductTransactionFilter.all(), new PageRequest(0, 10))
                .blockingGet();

        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.items()).hasSize(1);
    }

    @Test
    void findByProductWithStatusExplicitlyDiscardedStillFindsIt() {
        String productId = "prod-" + UUID.randomUUID();
        Transaction discarded = TransactionFixtures.failedWithdrawal("op-3", productId, "cust-A", "50.00", "X", "x")
                .discard(TransactionFixtures.CLOCK);
        adapter.save(discarded).blockingGet();

        var filter = new ProductTransactionFilter(null, null, TransactionStatus.DISCARDED);
        var page = adapter.findByProduct(productId, filter, new PageRequest(0, 10)).blockingGet();

        assertThat(page.totalElements()).isEqualTo(1);
    }

    @Test
    void findByProductWithTypeAndStatusBothSetUsesTheFourWayCombination() {
        String productId = "prod-" + UUID.randomUUID();
        adapter.save(TransactionFixtures.pendingDeposit("op-4", productId, "cust-A", "100.00")).blockingGet();
        adapter.save(TransactionFixtures.completedWithdrawal("op-5", productId, "cust-A", "50.00", "450.00"))
                .blockingGet();

        var filter = new ProductTransactionFilter(null, TransactionType.WITHDRAWAL, TransactionStatus.COMPLETED);
        var page = adapter.findByProduct(productId, filter, new PageRequest(0, 10)).blockingGet();

        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.items().get(0).type()).isEqualTo(TransactionType.WITHDRAWAL);
    }

    @Test
    void findAllScopesByCustomerIdRatherThanProductId() {
        String customerId = "cust-" + UUID.randomUUID();
        adapter.save(TransactionFixtures.pendingDeposit("op-6", "prod-a", customerId, "100.00")).blockingGet();
        adapter.save(TransactionFixtures.pendingDeposit("op-7", "prod-b", customerId, "100.00")).blockingGet();

        var page = adapter.findAll(new TransactionFilter(customerId, null, null, null), new PageRequest(0, 10))
                .blockingGet();

        assertThat(page.totalElements()).isEqualTo(2);
    }

    @Test
    void findAllHonorsTheDateRangeFilter() {
        String customerId = "cust-" + UUID.randomUUID();
        adapter.save(TransactionFixtures.pendingDeposit("op-8", "prod-a", customerId, "100.00")).blockingGet();

        DateRange farFuture = new DateRange(Instant.parse("2027-01-01T00:00:00Z"),
                Instant.parse("2027-02-01T00:00:00Z"));
        var page = adapter.findAll(new TransactionFilter(customerId, null, null, farFuture), new PageRequest(0, 10))
                .blockingGet();

        assertThat(page.totalElements()).isZero();
    }

    @Test
    void findPendingOlderThanServesTheRecoveryQuery() {
        adapter.save(TransactionFixtures.pendingDeposit("op-9", "prod-a", "cust-A", "100.00")).blockingGet();

        assertThat(adapter.findPendingOlderThan(Instant.parse("2030-01-01T00:00:00Z")).toList().blockingGet())
                .hasSize(1);
        assertThat(adapter.findPendingOlderThan(Instant.parse("2020-01-01T00:00:00Z")).toList().blockingGet())
                .isEmpty();
    }

    @Test
    void aOperationIdRaceIsResolvedByReReadingTheWinnerInsteadOfSurfacingTheRawError() {
        Transaction first = TransactionFixtures.pendingDeposit("op-race", "prod-a", "cust-A", "100.00");
        Transaction second = TransactionFixtures.pendingDeposit("op-race", "prod-a", "cust-A", "100.00");
        adapter.save(first).blockingGet();

        // Simulates the loser of a race between two identical concurrent requests: a second,
        // distinct Transaction (its own generated TransactionId) reusing the SAME operationId.
        // save() must not surface the raw DuplicateKeyException, it must come back with the
        // winner's own saved Transaction instead (data-model.md 3.1).
        Transaction result = adapter.save(second).blockingGet();

        assertThat(result.operationId()).isEqualTo(new OperationId("op-race"));
        assertThat(result.id()).isEqualTo(first.id()); // the winner's document, not a new one
        assertThat(repository.count().blockingGet()).isEqualTo(1L); // no duplicate document was inserted
    }
}
