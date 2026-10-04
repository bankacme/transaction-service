package com.bank.transaction.infrastructure.adapter.in.rest;

import com.bank.transaction.domain.model.Transaction;

/**
 * Not a domain exception: {@code RegisterDepositUseCase}/{@code RegisterWithdrawalUseCase} (R3)
 * return a rejected movement as a normal, successfully-completed {@code Single<Transaction>}
 * with {@code status=FAILED} — account-service already rejected it, there's nothing exceptional
 * about that from the use case's point of view. The contract's {@code MovementRejected} 422
 * body (ErrorResponse + {@code transactionId}) is a DIFFERENT shape than the generated
 * interface's declared success type ({@code MovementResponse}), so the only clean way to hand
 * Spring WebFlux a differently-shaped body is to route through the exception-handling path:
 * {@code DepositsAndWithdrawalsController} throws this once it sees {@code FAILED}, and {@link
 * GlobalExceptionHandler#handleMovementRejected} builds the real body — {@code @ExceptionHandler}
 * methods aren't bound to the controller interface's declared generic return type the way a
 * normal return value would be.
 */
class MovementRejectedException extends RuntimeException {

    private final transient Transaction transaction;

    MovementRejectedException(Transaction transaction) {
        super("Movement " + transaction.operationId().value() + " was rejected: "
                + transaction.failureReason().code());
        this.transaction = transaction;
    }

    Transaction transaction() {
        return transaction;
    }
}
