package com.bank.transaction.domain.model;

/**
 * Máquina de estados de la saga de transferencia (ficha, sección 3.1):
 *
 * <pre>
 * STARTED --debito ok--&gt; SOURCE_DEBITED --credito ok--&gt; COMPLETED
 *    |                        |
 *    | debito rechazado       | credito rechazado
 *    v                        v
 *  FAILED               COMPENSATING --reversa ok--&gt; COMPENSATED
 *                             |
 *                             +--reversa falla tras reintentos--&gt; COMPENSATION_FAILED
 * </pre>
 */
public enum TransferStatus {
    STARTED,
    SOURCE_DEBITED,
    COMPLETED,
    FAILED,
    COMPENSATING,
    COMPENSATED,
    COMPENSATION_FAILED
}
