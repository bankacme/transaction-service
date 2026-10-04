package com.bank.transaction.application.port.out;

import com.bank.transaction.domain.model.Money;
import com.bank.transaction.domain.model.MovementOutcome;
import com.bank.transaction.domain.model.OperationId;
import com.bank.transaction.domain.model.TransactionType;
import io.reactivex.rxjava3.core.Single;
import java.time.LocalDate;

/** REST + circuit breaker contra account-service en P1/P2; Kafka con correlación por
 *  operationId en P3 (ficha 4.2). {@code type} es DEPOSIT, WITHDRAWAL, TRANSFER_OUT o
 *  TRANSFER_IN únicamente — account-service solo entiende depósito/retiro de todas formas,
 *  así que el adaptador (R7) traduce TRANSFER_OUT/IN al tipo que corresponda. */
public interface AccountMovementPort {

    Single<MovementOutcome> apply(OperationId operationId, String accountId, TransactionType type, Money amount,
                                   LocalDate date);

    Single<MovementOutcome> reverse(OperationId operationId, String accountId);
}
