package com.bank.transaction.domain.model;

import java.time.Instant;

public record DateRange(Instant from, Instant to) {

    public DateRange {
        if (from == null || to == null) {
            throw new IllegalArgumentException("from and to are required");
        }
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from must not be after to");
        }
    }
}
