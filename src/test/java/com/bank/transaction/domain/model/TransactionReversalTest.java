package com.bank.transaction.domain.model;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TransactionReversalTest {

    private final OperationId reversalId = new OperationId("op-1-REV");

    @Test
    void rejectsAFailedOutcomeWithoutAReasonCode() {
        assertThatThrownBy(() -> new TransactionReversal(reversalId, ReversalOutcome.REVERSAL_FAILED, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsASuccessfulOutcomeWithAReasonCode() {
        assertThatThrownBy(() -> new TransactionReversal(reversalId, ReversalOutcome.REVERSED, "INSUFFICIENT_FUNDS"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
