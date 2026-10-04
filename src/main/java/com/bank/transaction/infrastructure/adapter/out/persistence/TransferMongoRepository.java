package com.bank.transaction.infrastructure.adapter.out.persistence;

import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import org.springframework.data.repository.reactive.RxJava3CrudRepository;

import java.time.Instant;
import java.util.Collection;

public interface TransferMongoRepository extends RxJava3CrudRepository<TransferDocument, String> {

    Maybe<TransferDocument> findByOperationId(String operationId);

    Flowable<TransferDocument> findBySourceAccountIdOrTargetAccountId(String sourceAccountId, String targetAccountId);

    Flowable<TransferDocument> findByStatus(String status);

    /** Regla 9/14: transferencias detenidas en curso, para la recuperación. */
    Flowable<TransferDocument> findByStatusInAndUpdatedAtBefore(Collection<String> statuses, Instant threshold);
}
