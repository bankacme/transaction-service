package com.bank.transaction.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Mismo VO y mismas reglas que account-service (data-model.md 1.3): escala 2, HALF_EVEN,
 * solo PEN, nunca negativo. {@code isZero()} es lo que usan las factories de Transaction y
 * TransferPolicy para la regla 1 (monto &gt; 0); Money en sí permite 0 porque
 * {@code resultingBalance} puede serlo.
 */
public record Money(BigDecimal amount, String currency) {

    private static final String PEN = "PEN";

    public Money {
        if (amount == null) {
            throw new IllegalArgumentException("Money amount must not be null");
        }
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("Money currency must not be blank");
        }
        if (!PEN.equals(currency)) {
            throw new IllegalArgumentException("Only PEN is supported in the demo, got: " + currency);
        }
        amount = amount.setScale(2, RoundingMode.HALF_EVEN);
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("Money amount must not be negative: " + amount);
        }
    }

    public static Money zero() {
        return new Money(BigDecimal.ZERO, PEN);
    }

    public static Money of(BigDecimal amount) {
        return new Money(amount, PEN);
    }

    public static Money of(String amount) {
        return of(new BigDecimal(amount));
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    public boolean isGreaterThan(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount) > 0;
    }

    public boolean isGreaterThanOrEqualTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount) >= 0;
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("Currency mismatch: " + currency + " vs " + other.currency);
        }
    }
}
