package com.bank.transaction.application.port.in;

import com.bank.transaction.application.command.RecordExternalMovementCommand;
import com.bank.transaction.domain.model.Transaction;
import io.reactivex.rxjava3.core.Single;

public interface RecordExternalMovementUseCase {

    /** Idempotente por operationId (regla 12): un duplicado devuelve el registro ya
     *  guardado. */
    Single<Transaction> execute(RecordExternalMovementCommand command);
}
