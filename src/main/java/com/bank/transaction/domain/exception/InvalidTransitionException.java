package com.bank.transaction.domain.exception;

/** Se intentó una transición de estado que no corresponde al estado actual de
 *  {@code Transaction} o {@code Transfer}: error defensivo, no debería ocurrir si el caso de
 *  uso respeta el flujo (a diferencia de {@link BusinessRuleViolationException}, que sí puede
 *  provocarla el cliente). */
public class InvalidTransitionException extends RuntimeException {

    public InvalidTransitionException(String message) {
        super(message);
    }
}
