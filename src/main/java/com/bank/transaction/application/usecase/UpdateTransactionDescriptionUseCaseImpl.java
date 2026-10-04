package com.bank.transaction.application.usecase;

import com.bank.transaction.application.command.UpdateTransactionDescriptionCommand;
import com.bank.transaction.application.port.in.UpdateTransactionDescriptionUseCase;
import com.bank.transaction.application.port.out.TransactionRepositoryPort;
import com.bank.transaction.domain.exception.TransactionNotFoundException;
import com.bank.transaction.domain.model.Transaction;
import io.reactivex.rxjava3.core.Single;
import java.time.Clock;

public class UpdateTransactionDescriptionUseCaseImpl implements UpdateTransactionDescriptionUseCase {

    private final TransactionRepositoryPort repositoryPort;
    private final Clock clock;

    public UpdateTransactionDescriptionUseCaseImpl(TransactionRepositoryPort repositoryPort, Clock clock) {
        this.repositoryPort = repositoryPort;
        this.clock = clock;
    }

    @Override
    public Single<Transaction> execute(UpdateTransactionDescriptionCommand command) {
        return repositoryPort.findById(command.transactionId())
                .switchIfEmpty(Single.error(new TransactionNotFoundException(command.transactionId().value())))
                .flatMap(tx -> Single.fromCallable(() -> tx.updateDescription(command.description(), clock)))
                .flatMap(repositoryPort::save);
    }
}
