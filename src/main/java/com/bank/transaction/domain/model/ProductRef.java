package com.bank.transaction.domain.model;

public record ProductRef(String productId, ProductType productType) {

    public ProductRef {
        if (productId == null || productId.isBlank()) {
            throw new IllegalArgumentException("productId must not be blank");
        }
        if (productType == null) {
            throw new IllegalArgumentException("productType must not be null");
        }
    }
}
