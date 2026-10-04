package com.bank.transaction.domain.model;

public record FailureReason(String code, String message) {

    public FailureReason {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
    }
}
