package com.bank.transaction.application.usecase;

import com.bank.transaction.application.port.in.DiscardTransactionUseCase;
import com.bank.transaction.application.port.out.TransactionRepositoryPort;
import com.bank.transaction.domain.exception.TransactionNotFoundException;
import com.bank.transaction.domain.model.TransactionId;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import java.time.Clock;

public class DiscardTransactionUseCaseImpl implements DiscardTransactionUseCase {

    private final TransactionRepositoryPort repositoryPort;
    private final Clock clock;

    public DiscardTransactionUseCaseImpl(TransactionRepositoryPort repositoryPort, Clock clock) {
        this.repositoryPort = repositoryPort;
        this.clock = clock;
    }

    @Override
    public Completable execute(TransactionId id) {
        return repositoryPort.findById(id)
                .switchIfEmpty(Single.error(new TransactionNotFoundException(id.value())))
                .flatMap(tx -> Single.fromCallable(() -> tx.discard(clock)))
                .flatMapCompletable(discarded -> repositoryPort.save(discarded).ignoreElement());
    }
}
