package com.bank.transaction.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void normalizesToTwoDecimalsWithHalfEven() {
        assertThat(Money.of(new BigDecimal("10.005")).amount()).isEqualByComparingTo("10.00");
        assertThat(Money.of(new BigDecimal("10.015")).amount()).isEqualByComparingTo("10.02");
    }

    @Test
    void rejectsAnyCurrencyOtherThanPen() {
        assertThatThrownBy(() -> new Money(BigDecimal.TEN, "USD")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsANegativeAmount() {
        assertThatThrownBy(() -> Money.of(new BigDecimal("-1.00"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void plusMinusAndComparisonsWorkOnTheSameCurrency() {
        Money ten = Money.of(new BigDecimal("10.00"));
        Money three = Money.of(new BigDecimal("3.00"));
        assertThat(ten.plus(three).amount()).isEqualByComparingTo("13.00");
        assertThat(ten.minus(three).amount()).isEqualByComparingTo("7.00");
        assertThat(ten.isGreaterThan(three)).isTrue();
        assertThat(three.isGreaterThan(ten)).isFalse();
        assertThat(Money.zero().isZero()).isTrue();
    }

    @Test
    void subtractingMoreThanTheAmountIsRejected() {
        Money three = Money.of(new BigDecimal("3.00"));
        Money ten = Money.of(new BigDecimal("10.00"));
        assertThatThrownBy(() -> three.minus(ten)).isInstanceOf(IllegalArgumentException.class);
    }
}
