package com.bank.transaction.application.port.in;

import com.bank.transaction.domain.model.TransactionId;
import io.reactivex.rxjava3.core.Completable;

public interface DiscardTransactionUseCase {

    Completable execute(TransactionId id);
}
