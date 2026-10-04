package com.bank.transaction.application.port.out;

import com.bank.transaction.application.port.in.TransferFilter;
import com.bank.transaction.domain.model.OperationId;
import com.bank.transaction.domain.model.Transfer;
import com.bank.transaction.domain.model.TransferId;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.time.Instant;

public interface TransferRepositoryPort {

    Single<Transfer> save(Transfer transfer);

    Maybe<Transfer> findById(TransferId id);

    Maybe<Transfer> findByOperationId(OperationId operationId);

    Flowable<Transfer> findAll(TransferFilter filter);

    /** Regla 9: transferencias STARTED/SOURCE_DEBITED/COMPENSATING más viejas que el
     *  umbral, para la recuperación. */
    Flowable<Transfer> findInProgressOlderThan(Instant threshold);
}
