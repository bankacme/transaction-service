package com.bank.transaction.application.usecase;

import com.bank.transaction.application.port.in.FindTransferUseCase;
import com.bank.transaction.application.port.out.TransferRepositoryPort;
import com.bank.transaction.domain.exception.TransferNotFoundException;
import com.bank.transaction.domain.model.Transfer;
import com.bank.transaction.domain.model.TransferId;
import io.reactivex.rxjava3.core.Single;

public class FindTransferUseCaseImpl implements FindTransferUseCase {

    private final TransferRepositoryPort repositoryPort;

    public FindTransferUseCaseImpl(TransferRepositoryPort repositoryPort) {
        this.repositoryPort = repositoryPort;
    }

    @Override
    public Single<Transfer> execute(TransferId id) {
        return repositoryPort.findById(id)
                .switchIfEmpty(Single.error(new TransferNotFoundException(id.value())));
    }
}
