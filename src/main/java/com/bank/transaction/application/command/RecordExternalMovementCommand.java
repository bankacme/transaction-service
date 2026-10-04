package com.bank.transaction.application.command;

import com.bank.transaction.domain.model.Money;
import com.bank.transaction.domain.model.OperationId;
import com.bank.transaction.domain.model.ProductRef;
import com.bank.transaction.domain.model.TransactionType;

/** POST /transactions/records (interno): credit-service ya aplicó el efecto, aquí solo se
 *  registra (regla 12). */
public record RecordExternalMovementCommand(OperationId operationId, ProductRef product, String customerId,
                                             TransactionType type, Money amount, Money resultingBalance,
                                             String description) {

    public RecordExternalMovementCommand {
        if (operationId == null || product == null) {
            throw new IllegalArgumentException("operationId and product are required");
        }
        if (customerId == null || customerId.isBlank()) {
            throw new IllegalArgumentException("customerId must not be blank");
        }
        if (type == null || amount == null || resultingBalance == null) {
            throw new IllegalArgumentException("type, amount and resultingBalance are required");
        }
    }
}
