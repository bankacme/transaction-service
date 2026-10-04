package com.bank.transaction.application.usecase;

import com.bank.transaction.application.port.in.FindProductTransactionsUseCase;
import com.bank.transaction.application.port.in.ProductTransactionFilter;
import com.bank.transaction.application.port.out.TransactionRepositoryPort;
import com.bank.transaction.application.view.PageRequest;
import com.bank.transaction.application.view.PageView;
import com.bank.transaction.domain.model.Transaction;
import io.reactivex.rxjava3.core.Single;

public class FindProductTransactionsUseCaseImpl implements FindProductTransactionsUseCase {

    private final TransactionRepositoryPort repositoryPort;

    public FindProductTransactionsUseCaseImpl(TransactionRepositoryPort repositoryPort) {
        this.repositoryPort = repositoryPort;
    }

    @Override
    public Single<PageView<Transaction>> execute(String productId, ProductTransactionFilter filter,
                                                  PageRequest page) {
        return repositoryPort.findByProduct(productId, filter, page);
    }
}
