package com.bank.transaction.domain.model;

/**
 * Identificador de idempotencia. El que llega del cliente tiene hasta 56 caracteres
 * (contrato, ver la ficha sección 12); los de las patas de una transferencia, su comisión y
 * su reversa se derivan del original con un sufijo fijo (regla 17) — de ahí los métodos de
 * instancia en vez de un factory estático, igual que decir "el OUT de este operationId".
 */
public record OperationId(String value) {

    private static final String TRANSFER_OUT_SUFFIX = "-OUT";
    private static final String TRANSFER_IN_SUFFIX = "-IN";
    private static final String REVERSAL_SUFFIX = "-REV";
    private static final String FEE_SUFFIX = "-FEE";

    public OperationId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("OperationId must not be blank");
        }
    }

    public OperationId forTransferOut() {
        return new OperationId(value + TRANSFER_OUT_SUFFIX);
    }

    public OperationId forTransferIn() {
        return new OperationId(value + TRANSFER_IN_SUFFIX);
    }

    public OperationId forReversal() {
        return new OperationId(value + REVERSAL_SUFFIX);
    }

    public OperationId forFee() {
        return new OperationId(value + FEE_SUFFIX);
    }
}
