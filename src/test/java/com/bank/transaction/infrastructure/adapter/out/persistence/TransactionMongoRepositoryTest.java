package com.bank.transaction.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Range;

/**
 * Against a real local Mongo replica set — same approach as account-service's own {@code
 * AccountMongoRepositoryTest} and customer-service's {@code CustomerMongoRepositoryTest}:
 * exercises the derived queries and confirms the indexes {@link TransactionIndexInitializer}
 * creates at startup actually reject/serve what data-model.md sections 3.1/4 say they must.
 * Not runnable in this sandbox (no Mongo, no network to resolve Spring Boot test
 * dependencies) — see R4's delivery notes.
 */
@SpringBootTest
class TransactionMongoRepositoryTest {

    @Autowired
    private TransactionMongoRepository repository;

    @BeforeEach
    @AfterEach
    void cleanCollection() {
        repository.deleteAll().blockingAwait();
    }

    @Test
    void theUniqueIndexRejectsADuplicateOperationId() {
        repository.save(minimalTransaction("tx-1", "op-dup", "prod-1", "cust-A", "DEPOSIT", "COMPLETED",
                Instant.parse("2026-09-01T10:00:00Z"))).blockingGet();

        assertThatThrownBy(() -> repository.save(minimalTransaction("tx-2", "op-dup", "prod-1", "cust-A", "DEPOSIT",
                "COMPLETED", Instant.parse("2026-09-01T10:05:00Z"))).blockingGet())
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("uk_tx_operation_id");
    }

    @Test
    void findByOperationIdReturnsWhatWasSaved() {
        repository.save(minimalTransaction("tx-3", "op-find", "prod-1", "cust-A", "DEPOSIT", "COMPLETED",
                Instant.parse("2026-09-01T10:00:00Z"))).blockingGet();

        assertThat(repository.findByOperationId("op-find").blockingGet().getId()).isEqualTo("tx-3");
        assertThat(repository.findByOperationId("op-missing").isEmpty().blockingGet()).isTrue();
    }

    @Test
    void productHistoryWithNoFiltersExcludesDiscardedAndRespectsTheDateRange() {
        String productId = "prod-" + UUID.randomUUID();
        Instant inRange = Instant.parse("2026-09-15T10:00:00Z");
        Instant outOfRange = Instant.parse("2026-08-01T10:00:00Z");
        repository.save(minimalTransaction("tx-4", "op-4", productId, "cust-A", "DEPOSIT", "COMPLETED", inRange))
                .blockingGet();
        repository.save(minimalTransaction("tx-5", "op-5", productId, "cust-A", "WITHDRAWAL", "DISCARDED", inRange))
                .blockingGet();
        repository.save(minimalTransaction("tx-6", "op-6", productId, "cust-A", "DEPOSIT", "COMPLETED", outOfRange))
                .blockingGet();

        Range<Instant> september = Range.from(Range.Bound.inclusive(Instant.parse("2026-09-01T00:00:00Z")))
                .to(Range.Bound.exclusive(Instant.parse("2026-10-01T00:00:00Z")));

        assertThat(repository.findByProductIdAndStatusNotAndOccurredAtBetween(productId, "DISCARDED", september,
                PageRequest.of(0, 10)).toList().blockingGet()).hasSize(1);
        assertThat(repository.countByProductIdAndStatusNotAndOccurredAtBetween(productId, "DISCARDED", september)
                .blockingGet()).isEqualTo(1L);
    }

    @Test
    void productHistoryWithTypeAndStatusBothSetUsesTheFourWayCombination() {
        String productId = "prod-" + UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-09-15T10:00:00Z");
        repository.save(minimalTransaction("tx-7", "op-7", productId, "cust-A", "DEPOSIT", "COMPLETED", occurredAt))
                .blockingGet();
        repository.save(minimalTransaction("tx-8", "op-8", productId, "cust-A", "WITHDRAWAL", "COMPLETED",
                occurredAt)).blockingGet();

        Range<Instant> september = Range.from(Range.Bound.inclusive(Instant.parse("2026-09-01T00:00:00Z")))
                .to(Range.Bound.exclusive(Instant.parse("2026-10-01T00:00:00Z")));

        assertThat(repository.findByProductIdAndTypeAndStatusAndOccurredAtBetween(productId, "DEPOSIT", "COMPLETED",
                september, PageRequest.of(0, 10)).toList().blockingGet()).hasSize(1);
        assertThat(repository.countByProductIdAndTypeAndStatusAndOccurredAtBetween(productId, "WITHDRAWAL",
                "COMPLETED", september).blockingGet()).isEqualTo(1L);
    }

    @Test
    void customerHistoryIsScopedByCustomerIdRatherThanProductId() {
        String customerId = "cust-" + UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-09-15T10:00:00Z");
        repository.save(minimalTransaction("tx-9", "op-9", "prod-a", customerId, "DEPOSIT", "COMPLETED", occurredAt))
                .blockingGet();
        repository.save(minimalTransaction("tx-10", "op-10", "prod-b", customerId, "DEPOSIT", "COMPLETED",
                occurredAt)).blockingGet();

        Range<Instant> september = Range.from(Range.Bound.inclusive(Instant.parse("2026-09-01T00:00:00Z")))
                .to(Range.Bound.exclusive(Instant.parse("2026-10-01T00:00:00Z")));

        assertThat(repository.findByCustomerIdAndStatusNotAndOccurredAtBetween(customerId, "DISCARDED", september,
                PageRequest.of(0, 10)).toList().blockingGet()).hasSize(2);
    }

    @Test
    void findByStatusAndCreatedAtBeforeServesTheRecoveryQuery() {
        Instant old = Instant.parse("2026-09-01T09:00:00Z");
        Instant recent = Instant.parse("2026-09-28T09:58:00Z");
        repository.save(minimalTransactionWithCreatedAt("tx-11", "op-11", "prod-1", "cust-A", "DEPOSIT", "PENDING",
                old, old)).blockingGet();
        repository.save(minimalTransactionWithCreatedAt("tx-12", "op-12", "prod-1", "cust-A", "DEPOSIT", "PENDING",
                recent, recent)).blockingGet();

        Instant threshold = Instant.parse("2026-09-28T09:50:00Z");

        assertThat(repository.findByStatusAndCreatedAtBefore("PENDING", threshold).toList().blockingGet())
                .extracting(TransactionDocument::getId)
                .containsExactly("tx-11");
    }

    private TransactionDocument minimalTransaction(String id, String operationId, String productId,
                                                      String customerId, String type, String status,
                                                      Instant occurredAt) {
        return minimalTransactionWithCreatedAt(id, operationId, productId, customerId, type, status, occurredAt,
                occurredAt);
    }

    private TransactionDocument minimalTransactionWithCreatedAt(String id, String operationId, String productId,
                                                                   String customerId, String type, String status,
                                                                   Instant occurredAt, Instant createdAt) {
        return TransactionDocument.builder()
                .id(id)
                .operationId(operationId)
                .productId(productId)
                .productType("ACCOUNT")
                .customerId(customerId)
                .type(type)
                .amount(new BigDecimal("100.00"))
                .status(status)
                .occurredAt(occurredAt)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build();
    }
}
