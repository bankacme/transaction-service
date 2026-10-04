package com.bank.transaction.application.port.in;

import com.bank.transaction.application.command.RegisterMovementCommand;
import com.bank.transaction.domain.model.Transaction;
import io.reactivex.rxjava3.core.Single;

/** Idempotente por operationId (regla 2): repetir la misma petición devuelve el resultado
 *  original; si account-service no responde a tiempo, el movimiento queda PENDING (202) y
 *  se reintenta con el mismo operationId (regla 14). */
public interface RegisterDepositUseCase {

    Single<Transaction> execute(RegisterMovementCommand command);
}
