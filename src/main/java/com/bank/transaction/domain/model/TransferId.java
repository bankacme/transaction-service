package com.bank.transaction.domain.model;

import java.util.UUID;

public record TransferId(String value) {

    public TransferId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("TransferId must not be blank");
        }
    }

    public static TransferId newId() {
        return new TransferId(UUID.randomUUID().toString());
    }
}
