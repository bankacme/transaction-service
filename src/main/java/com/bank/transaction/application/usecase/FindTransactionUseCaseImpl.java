package com.bank.transaction.application.usecase;

import com.bank.transaction.application.port.in.FindTransactionUseCase;
import com.bank.transaction.application.port.out.TransactionRepositoryPort;
import com.bank.transaction.domain.exception.TransactionNotFoundException;
import com.bank.transaction.domain.model.Transaction;
import com.bank.transaction.domain.model.TransactionId;
import io.reactivex.rxjava3.core.Single;

public class FindTransactionUseCaseImpl implements FindTransactionUseCase {

    private final TransactionRepositoryPort repositoryPort;

    public FindTransactionUseCaseImpl(TransactionRepositoryPort repositoryPort) {
        this.repositoryPort = repositoryPort;
    }

    @Override
    public Single<Transaction> execute(TransactionId id) {
        return repositoryPort.findById(id)
                .switchIfEmpty(Single.error(new TransactionNotFoundException(id.value())));
    }
}
