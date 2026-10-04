package com.bank.transaction.application.port.out;

import com.bank.transaction.application.port.in.ProductTransactionFilter;
import com.bank.transaction.application.port.in.TransactionFilter;
import com.bank.transaction.application.view.PageRequest;
import com.bank.transaction.application.view.PageView;
import com.bank.transaction.domain.model.OperationId;
import com.bank.transaction.domain.model.Transaction;
import com.bank.transaction.domain.model.TransactionId;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.time.Instant;

public interface TransactionRepositoryPort {

    Single<Transaction> save(Transaction transaction);

    Maybe<Transaction> findById(TransactionId id);

    Maybe<Transaction> findByOperationId(OperationId operationId);

    /** Historial de un producto (FindProductTransactionsUseCase): más recientes primero
     *  (regla 15). Producto sin movimientos: página vacía, nunca un error. */
    Single<PageView<Transaction>> findByProduct(String productId, ProductTransactionFilter filter, PageRequest page);

    /** Consulta administrativa (FindTransactionsUseCase); {@link TransactionFilter#customerId()}
     *  es obligatorio ahí, no aquí. */
    Single<PageView<Transaction>> findAll(TransactionFilter filter, PageRequest page);

    /** Regla 14: movimientos PENDING más viejos que el umbral, para la recuperación. */
    Flowable<Transaction> findPendingOlderThan(Instant threshold);
}
