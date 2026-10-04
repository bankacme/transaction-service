package com.bank.transaction.application.port.in;

import com.bank.transaction.application.command.RegisterMovementCommand;
import com.bank.transaction.domain.model.Transaction;
import io.reactivex.rxjava3.core.Single;

/** Mismo contrato que RegisterDepositUseCase, para retiros. */
public interface RegisterWithdrawalUseCase {

    Single<Transaction> execute(RegisterMovementCommand command);
}
