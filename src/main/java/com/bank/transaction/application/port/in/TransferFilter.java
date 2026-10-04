package com.bank.transaction.application.port.in;

import com.bank.transaction.domain.model.TransferStatus;

/** GET /transfers (contrato): accountId, status, operationId, todos opcionales. */
public record TransferFilter(String accountId, TransferStatus status, String operationId) {

    public static TransferFilter all() {
        return new TransferFilter(null, null, null);
    }
}
