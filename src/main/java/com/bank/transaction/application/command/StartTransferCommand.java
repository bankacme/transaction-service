package com.bank.transaction.application.command;

import com.bank.transaction.domain.model.Money;
import com.bank.transaction.domain.model.OperationId;

public record StartTransferCommand(OperationId operationId, String sourceAccountId, String targetAccountId,
                                    Money amount, String description, String requestedBy) {

    public StartTransferCommand {
        if (operationId == null) {
            throw new IllegalArgumentException("operationId is required");
        }
        if (sourceAccountId == null || sourceAccountId.isBlank() || targetAccountId == null
                || targetAccountId.isBlank()) {
            throw new IllegalArgumentException("sourceAccountId and targetAccountId must not be blank");
        }
        if (amount == null) {
            throw new IllegalArgumentException("amount is required");
        }
        if (requestedBy == null || requestedBy.isBlank()) {
            throw new IllegalArgumentException("requestedBy must not be blank");
        }
    }
}
