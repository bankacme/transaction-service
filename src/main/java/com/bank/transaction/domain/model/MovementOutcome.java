package com.bank.transaction.domain.model;

/**
 * Resultado de pedirle a account-service aplicar o revertir un movimiento
 * (AccountMovementPort, ficha sección 4.2): aplicado (con saldo resultante y comisión
 * opcional) o rechazado (con el motivo). Nunca ambos a la vez.
 */
public record MovementOutcome(OperationId operationId, boolean applied, Money resultingBalance, Money fee,
                               FailureReason failureReason) {

    public MovementOutcome {
        if (operationId == null) {
            throw new IllegalArgumentException("operationId is required");
        }
        if (applied) {
            if (resultingBalance == null) {
                throw new IllegalArgumentException("resultingBalance is required when applied");
            }
            if (failureReason != null) {
                throw new IllegalArgumentException("failureReason must be null when applied");
            }
        } else {
            if (failureReason == null) {
                throw new IllegalArgumentException("failureReason is required when rejected");
            }
            if (resultingBalance != null || fee != null) {
                throw new IllegalArgumentException("resultingBalance/fee must be null when rejected");
            }
        }
    }

    public static MovementOutcome applied(OperationId operationId, Money resultingBalance, Money fee) {
        return new MovementOutcome(operationId, true, resultingBalance, fee, null);
    }

    public static MovementOutcome rejected(OperationId operationId, FailureReason failureReason) {
        return new MovementOutcome(operationId, false, null, null, failureReason);
    }
}
