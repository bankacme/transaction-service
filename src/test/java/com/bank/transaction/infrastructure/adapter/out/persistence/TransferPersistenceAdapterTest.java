package com.bank.transaction.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.transaction.application.port.in.TransferFilter;
import com.bank.transaction.domain.model.OperationId;
import com.bank.transaction.domain.model.TransactionId;
import com.bank.transaction.domain.model.Transfer;
import com.bank.transaction.domain.model.TransferStatus;
import com.bank.transaction.infrastructure.fixture.TransactionFixtures;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The adapter-level behaviors {@link TransferPersistenceAdapter}'s own javadoc promises: the
 * broad-query-by-priority dispatch ({@code operationId} > {@code accountId} > {@code status} >
 * everything) plus in-memory filtering/{@code take(200)}, and the {@code
 * uk_transfer_operation_id} race recovery. Not runnable in this sandbox (no Mongo) — see R4's
 * delivery notes.
 */
@SpringBootTest
class TransferPersistenceAdapterTest {

    @Autowired
    private TransferPersistenceAdapter adapter;

    @Autowired
    private TransferMongoRepository repository;

    @BeforeEach
    @AfterEach
    void cleanCollection() {
        repository.deleteAll().blockingAwait();
    }

    @Test
    void findAllByOperationIdUsesTheNarrowestQueryButStillAppliesEveryGivenFilterAsAnAnd() {
        adapter.save(TransactionFixtures.startedTransfer("op-1", "acc-A", "acc-B", "100.00")).blockingGet();
        adapter.save(TransactionFixtures.startedTransfer("op-2", "acc-C", "acc-D", "200.00")).blockingGet();

        // operationId alone still finds it (no other filter to AND against).
        TransferFilter byOperationIdAlone = new TransferFilter(null, null, "op-1");
        assertThat(adapter.findAll(byOperationIdAlone).toList().blockingGet())
                .extracting(transfer -> transfer.operationId().value()).containsExactly("op-1");

        // operationId plus matching accountId/status: still found (consistent filters).
        TransferFilter matching = new TransferFilter("acc-A", TransferStatus.STARTED, "op-1");
        assertThat(adapter.findAll(matching).toList().blockingGet()).hasSize(1);

        // operationId plus a field that does NOT match op-1's own transfer: every given
        // filter is an AND, including the one used to pick the broad query — the broad
        // query is only a DB-round-trip optimization, not an override of the rest.
        TransferFilter contradicting = new TransferFilter("acc-does-not-exist", null, "op-1");
        assertThat(adapter.findAll(contradicting).toList().blockingGet()).isEmpty();
    }

    @Test
    void findAllByAccountIdMatchesEitherSourceOrTarget() {
        adapter.save(TransactionFixtures.startedTransfer("op-3", "acc-A", "acc-B", "100.00")).blockingGet();
        adapter.save(TransactionFixtures.startedTransfer("op-4", "acc-X", "acc-A", "100.00")).blockingGet();
        adapter.save(TransactionFixtures.startedTransfer("op-5", "acc-Y", "acc-Z", "100.00")).blockingGet();

        TransferFilter filter = new TransferFilter("acc-A", null, null);

        assertThat(adapter.findAll(filter).toList().blockingGet()).hasSize(2);
    }

    @Test
    void findAllByAccountIdAlsoFiltersByStatusInMemory() {
        adapter.save(TransactionFixtures.startedTransfer("op-6", "acc-A", "acc-B", "100.00")).blockingGet();
        Transfer compensated = TransactionFixtures.compensatedTransfer("op-7", "acc-A", "acc-C", "100.00");
        adapter.save(compensated).blockingGet();

        TransferFilter filter = new TransferFilter("acc-A", TransferStatus.COMPENSATED, null);

        var results = adapter.findAll(filter).toList().blockingGet();

        assertThat(results).hasSize(1);
        assertThat(results.get(0).operationId()).isEqualTo(new OperationId("op-7"));
    }

    @Test
    void findAllFallsBackToStatusThenToEverythingWhenNothingMoreSpecificIsGiven() {
        adapter.save(TransactionFixtures.startedTransfer("op-8", "acc-A", "acc-B", "100.00")).blockingGet();
        adapter.save(TransactionFixtures.compensatedTransfer("op-9", "acc-C", "acc-D", "100.00")).blockingGet();

        assertThat(adapter.findAll(new TransferFilter(null, TransferStatus.STARTED, null)).toList().blockingGet())
                .hasSize(1);
        assertThat(adapter.findAll(new TransferFilter(null, null, null)).toList().blockingGet()).hasSize(2);
    }

    @Test
    void findInProgressOlderThanMatchesStartedSourceDebitedAndCompensatingButNotTerminalStatuses() {
        // STARTED is one of the three in-progress statuses (data-model.md 2.6: a Transfer can
        // be recovered from STARTED too — RecoverPendingOperationsUseCaseImpl.retryDebit), so
        // it must match; COMPENSATED is terminal and must not.
        adapter.save(TransactionFixtures.startedTransfer("op-10", "acc-A", "acc-B", "100.00")).blockingGet();
        adapter.save(TransactionFixtures.compensatedTransfer("op-11", "acc-C", "acc-D", "100.00")).blockingGet();
        Transfer sourceDebited = TransactionFixtures.startedTransfer("op-12", "acc-E", "acc-F", "100.00")
                .sourceDebited(TransactionId.newId(), TransactionFixtures.CLOCK);
        adapter.save(sourceDebited).blockingGet();

        var results = adapter.findInProgressOlderThan(Instant.parse("2030-01-01T00:00:00Z")).toList().blockingGet();

        assertThat(results).extracting(transfer -> transfer.operationId().value())
                .containsExactlyInAnyOrder("op-10", "op-12");
    }

    @Test
    void aOperationIdRaceIsResolvedByReReadingTheWinnerInsteadOfSurfacingTheRawError() {
        Transfer first = TransactionFixtures.startedTransfer("op-race", "acc-A", "acc-B", "100.00");
        Transfer second = TransactionFixtures.startedTransfer("op-race", "acc-A", "acc-B", "100.00");
        adapter.save(first).blockingGet();

        Transfer result = adapter.save(second).blockingGet();

        assertThat(result.operationId()).isEqualTo(new OperationId("op-race"));
        assertThat(result.id()).isEqualTo(first.id());
        assertThat(repository.count().blockingGet()).isEqualTo(1L);
    }
}
