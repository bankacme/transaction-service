package com.bank.transaction.domain.event;

import com.bank.transaction.domain.model.Transaction;

/** Se publica cuando account-service rechazó la reversa (después de
 *  {@code Transaction.markReversalFailed}); el movimiento original sigue COMPLETED. */
public record TransactionReversalFailed(String operationId, String originalOperationId, String reasonCode)
        implements TransactionDomainEvent {

    public static TransactionReversalFailed from(Transaction originalTransaction) {
        return new TransactionReversalFailed(
                originalTransaction.reversal().operationId().value(),
                originalTransaction.operationId().value(),
                originalTransaction.reversal().reasonCode());
    }
}
