package com.bank.transaction.domain.model;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MovementOutcomeTest {

    private final OperationId operationId = new OperationId("op-1");

    @Test
    void appliedRequiresAResultingBalance() {
        assertThatThrownBy(() -> new MovementOutcome(operationId, true, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void appliedMustNotCarryAFailureReason() {
        Money balance = Money.of(new BigDecimal("100.00"));
        FailureReason reason = new FailureReason("INSUFFICIENT_FUNDS", "nope");
        assertThatThrownBy(() -> new MovementOutcome(operationId, true, balance, null, reason))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectedRequiresAFailureReason() {
        assertThatThrownBy(() -> new MovementOutcome(operationId, false, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectedMustNotCarryABalanceOrFee() {
        Money balance = Money.of(new BigDecimal("100.00"));
        FailureReason reason = new FailureReason("INSUFFICIENT_FUNDS", "nope");
        assertThatThrownBy(() -> new MovementOutcome(operationId, false, balance, null, reason))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
