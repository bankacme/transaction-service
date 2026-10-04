package com.bank.transaction.application.usecase;

import com.bank.transaction.application.port.in.FindTransfersUseCase;
import com.bank.transaction.application.port.in.TransferFilter;
import com.bank.transaction.application.port.out.TransferRepositoryPort;
import com.bank.transaction.domain.model.Transfer;
import io.reactivex.rxjava3.core.Flowable;

public class FindTransfersUseCaseImpl implements FindTransfersUseCase {

    private final TransferRepositoryPort repositoryPort;

    public FindTransfersUseCaseImpl(TransferRepositoryPort repositoryPort) {
        this.repositoryPort = repositoryPort;
    }

    @Override
    public Flowable<Transfer> execute(TransferFilter filter) {
        return repositoryPort.findAll(filter);
    }
}
