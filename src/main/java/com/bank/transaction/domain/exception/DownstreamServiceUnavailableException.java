package com.bank.transaction.domain.exception;

/**
 * An outbound call to {@code account-service} (via {@link
 * com.bank.transaction.application.port.out.AccountMovementPort} or {@link
 * com.bank.transaction.application.port.out.AccountLookupPort}) did not answer in time, or its
 * circuit breaker is open. 503 {@code SERVICE_UNAVAILABLE} (contract; data-model.md section 6,
 * common-schemas.yaml's {@code ServiceUnavailable} response): "Otro servicio no respondió en 2 s
 * o el circuito está abierto" covers both cases with the same code on purpose — same reasoning,
 * same name, as account-service's own exception of this name (it wraps a {@code TimeLimiter}
 * timeout, a Resilience4j {@code CallNotPermittedException}, or any other transport failure).
 *
 * <p>Declared now, in R5, so {@link
 * com.bank.transaction.infrastructure.adapter.in.rest.GlobalExceptionHandler} has a concrete
 * type to translate into the contract's 503 from day one; nothing throws it yet, since the real
 * REST client adapter for {@code account-service} (with its circuit breaker) is R7's job —
 * {@code infrastructure/adapter/out/client} is still empty.
 */
public class DownstreamServiceUnavailableException extends RuntimeException {

    public DownstreamServiceUnavailableException(String serviceName, Throwable cause) {
        super(serviceName + " did not respond in time", cause);
    }
}
