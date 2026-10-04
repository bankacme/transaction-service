package com.bank.transaction.application.usecase;

import com.bank.transaction.application.port.in.FindTransactionsUseCase;
import com.bank.transaction.application.port.in.TransactionFilter;
import com.bank.transaction.application.port.out.TransactionRepositoryPort;
import com.bank.transaction.application.view.PageRequest;
import com.bank.transaction.application.view.PageView;
import com.bank.transaction.domain.model.Transaction;
import io.reactivex.rxjava3.core.Single;

/** customerId es obligatorio en esta consulta (contrato, ficha sección 5); se valida aquí
 *  porque TransactionFilter se mantiene simétrico a ProductTransactionFilter (todo
 *  opcional a nivel de record). */
public class FindTransactionsUseCaseImpl implements FindTransactionsUseCase {

    private final TransactionRepositoryPort repositoryPort;

    public FindTransactionsUseCaseImpl(TransactionRepositoryPort repositoryPort) {
        this.repositoryPort = repositoryPort;
    }

    @Override
    public Single<PageView<Transaction>> execute(TransactionFilter filter, PageRequest page) {
        if (filter.customerId() == null || filter.customerId().isBlank()) {
            return Single.error(new IllegalArgumentException("customerId is required for this query"));
        }
        return repositoryPort.findAll(filter, page);
    }
}
