package com.bank.transaction.domain.exception;

/**
 * Una sola excepción para cada 422 de negocio, distinguida por {@link #getErrorCode()} —
 * mismo patrón que account-service. Ver la ficha de transaction-service, sección 5, para la
 * lista completa de códigos.
 */
public class BusinessRuleViolationException extends RuntimeException {

    private final String errorCode;

    public BusinessRuleViolationException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
