package com.bank.transaction.domain.model;

import java.util.UUID;

public record TransactionId(String value) {

    public TransactionId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("TransactionId must not be blank");
        }
    }

    public static TransactionId newId() {
        return new TransactionId(UUID.randomUUID().toString());
    }
}
