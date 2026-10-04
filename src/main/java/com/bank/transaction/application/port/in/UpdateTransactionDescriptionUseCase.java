package com.bank.transaction.application.port.in;

import com.bank.transaction.application.command.UpdateTransactionDescriptionCommand;
import com.bank.transaction.domain.model.Transaction;
import io.reactivex.rxjava3.core.Single;

public interface UpdateTransactionDescriptionUseCase {

    Single<Transaction> execute(UpdateTransactionDescriptionCommand command);
}
