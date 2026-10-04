package com.bank.transaction.application.usecase;

import com.bank.transaction.application.command.RegisterMovementCommand;
import com.bank.transaction.application.port.in.RegisterWithdrawalUseCase;
import com.bank.transaction.application.port.out.AccountLookupPort;
import com.bank.transaction.application.port.out.AccountMovementPort;
import com.bank.transaction.application.port.out.TransactionEventPublisherPort;
import com.bank.transaction.application.port.out.TransactionRepositoryPort;
import com.bank.transaction.application.port.out.UnitOfWorkPort;
import com.bank.transaction.domain.model.Transaction;
import com.bank.transaction.domain.model.TransactionType;
import io.reactivex.rxjava3.core.Single;
import java.time.Clock;

public class RegisterWithdrawalUseCaseImpl extends AbstractRegisterMovementUseCase
        implements RegisterWithdrawalUseCase {

    public RegisterWithdrawalUseCaseImpl(TransactionRepositoryPort transactionRepositoryPort,
            AccountLookupPort accountLookupPort, AccountMovementPort accountMovementPort,
            UnitOfWorkPort unitOfWorkPort, TransactionEventPublisherPort eventPublisherPort, Clock clock) {
        super(TransactionType.WITHDRAWAL, transactionRepositoryPort, accountLookupPort, accountMovementPort,
                unitOfWorkPort, eventPublisherPort, clock);
    }

    @Override
    public Single<Transaction> execute(RegisterMovementCommand command) {
        return doExecute(command);
    }
}
