package com.bank.transaction.domain.model;

/** Resultado de pedirle a account-service revertir un movimiento (distinto del estado
 *  COMPENSATING/COMPENSATED/COMPENSATION_FAILED de Transfer, que es el de la saga completa:
 *  este es el resultado puntual de un solo intento de reversa sobre un Transaction). */
public enum ReversalOutcome {
    REVERSED,
    REVERSAL_FAILED
}
