package com.bank.transaction.infrastructure.adapter.out.persistence;

import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.time.Instant;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Range;
import org.springframework.data.repository.reactive.RxJava3CrudRepository;

public interface TransactionMongoRepository extends RxJava3CrudRepository<TransactionDocument, String> {

    Maybe<TransactionDocument> findByOperationId(String operationId);

    // --- historial de un producto (FindProductTransactionsUseCase) ---

    Flowable<TransactionDocument> findByProductIdAndStatusNotAndOccurredAtBetween(
            String productId, String excludedStatus, Range<Instant> occurredAt, Pageable pageable);

    Single<Long> countByProductIdAndStatusNotAndOccurredAtBetween(
            String productId, String excludedStatus, Range<Instant> occurredAt);

    Flowable<TransactionDocument> findByProductIdAndTypeAndStatusNotAndOccurredAtBetween(
            String productId, String type, String excludedStatus, Range<Instant> occurredAt, Pageable pageable);

    Single<Long> countByProductIdAndTypeAndStatusNotAndOccurredAtBetween(
            String productId, String type, String excludedStatus, Range<Instant> occurredAt);

    Flowable<TransactionDocument> findByProductIdAndStatusAndOccurredAtBetween(
            String productId, String status, Range<Instant> occurredAt, Pageable pageable);

    Single<Long> countByProductIdAndStatusAndOccurredAtBetween(
            String productId, String status, Range<Instant> occurredAt);

    Flowable<TransactionDocument> findByProductIdAndTypeAndStatusAndOccurredAtBetween(
            String productId, String type, String status, Range<Instant> occurredAt, Pageable pageable);

    Single<Long> countByProductIdAndTypeAndStatusAndOccurredAtBetween(
            String productId, String type, String status, Range<Instant> occurredAt);

    // --- consulta administrativa por cliente (FindTransactionsUseCase) ---

    Flowable<TransactionDocument> findByCustomerIdAndStatusNotAndOccurredAtBetween(
            String customerId, String excludedStatus, Range<Instant> occurredAt, Pageable pageable);

    Single<Long> countByCustomerIdAndStatusNotAndOccurredAtBetween(
            String customerId, String excludedStatus, Range<Instant> occurredAt);

    Flowable<TransactionDocument> findByCustomerIdAndTypeAndStatusNotAndOccurredAtBetween(
            String customerId, String type, String excludedStatus, Range<Instant> occurredAt, Pageable pageable);

    Single<Long> countByCustomerIdAndTypeAndStatusNotAndOccurredAtBetween(
            String customerId, String type, String excludedStatus, Range<Instant> occurredAt);

    Flowable<TransactionDocument> findByCustomerIdAndStatusAndOccurredAtBetween(
            String customerId, String status, Range<Instant> occurredAt, Pageable pageable);

    Single<Long> countByCustomerIdAndStatusAndOccurredAtBetween(
            String customerId, String status, Range<Instant> occurredAt);

    Flowable<TransactionDocument> findByCustomerIdAndTypeAndStatusAndOccurredAtBetween(
            String customerId, String type, String status, Range<Instant> occurredAt, Pageable pageable);

    Single<Long> countByCustomerIdAndTypeAndStatusAndOccurredAtBetween(
            String customerId, String type, String status, Range<Instant> occurredAt);

    // --- recuperación (regla 14 / data-model.md 2.6) ---

    Flowable<TransactionDocument> findByStatusAndCreatedAtBefore(String status, Instant threshold);
}
