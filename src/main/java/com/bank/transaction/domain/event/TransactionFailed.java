package com.bank.transaction.domain.event;

import com.bank.transaction.domain.model.Transaction;

public record TransactionFailed(String transactionId, String operationId, String productId, String reasonCode)
        implements TransactionDomainEvent {

    public static TransactionFailed from(Transaction transaction) {
        return new TransactionFailed(
                transaction.id().value(),
                transaction.operationId().value(),
                transaction.product().productId(),
                transaction.failureReason() == null ? null : transaction.failureReason().code());
    }
}
