package com.bank.transaction.application.command;

import com.bank.transaction.domain.model.TransactionId;

public record UpdateTransactionDescriptionCommand(TransactionId transactionId, String description) {

    public UpdateTransactionDescriptionCommand {
        if (transactionId == null) {
            throw new IllegalArgumentException("transactionId is required");
        }
    }
}
