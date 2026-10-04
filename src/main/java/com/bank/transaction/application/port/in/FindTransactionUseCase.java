package com.bank.transaction.application.port.in;

import com.bank.transaction.domain.model.Transaction;
import com.bank.transaction.domain.model.TransactionId;
import io.reactivex.rxjava3.core.Single;

public interface FindTransactionUseCase {

    Single<Transaction> execute(TransactionId id);
}
