package com.bank.transaction.domain.model;

/**
 * Anota en el Transaction original el intento de reversa, haya tenido éxito o no (ficha,
 * sección 3.1: "reversal (opcional: operationId &lt;op&gt;-REV, outcome, reasonCode)"). Con
 * REVERSED el aggregate pasa a status REVERSED (Transaction.markReversed); con
 * REVERSAL_FAILED el aggregate se queda como estaba, solo queda la anotación para
 * trazabilidad y para que la recuperación sepa que ya hubo un intento (Transaction.
 * markReversalFailed).
 */
public record TransactionReversal(OperationId operationId, ReversalOutcome outcome, String reasonCode) {

    public TransactionReversal {
        if (operationId == null || outcome == null) {
            throw new IllegalArgumentException("operationId and outcome are required");
        }
        if (outcome == ReversalOutcome.REVERSAL_FAILED && (reasonCode == null || reasonCode.isBlank())) {
            throw new IllegalArgumentException("reasonCode is required when the reversal failed");
        }
        if (outcome == ReversalOutcome.REVERSED && reasonCode != null) {
            throw new IllegalArgumentException("reasonCode must be null when the reversal succeeded");
        }
    }
}
