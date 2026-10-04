package com.bank.transaction.infrastructure.adapter.in.rest;

import com.bank.transaction.domain.exception.AccountNotFoundException;
import com.bank.transaction.domain.exception.BusinessRuleViolationException;
import com.bank.transaction.domain.exception.DownstreamServiceUnavailableException;
import com.bank.transaction.domain.exception.TransactionNotFoundException;
import com.bank.transaction.domain.exception.TransferNotFoundException;
import com.bank.transaction.infrastructure.adapter.in.rest.dto.ErrorResponse;
import com.bank.transaction.infrastructure.adapter.in.rest.dto.FieldError;
import jakarta.validation.ConstraintViolationException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.server.ServerWebExchange;

/**
 * Translates domain/application exceptions and Bean Validation failures into the standard
 * {@code ErrorResponse} body (contract; data-model.md section 6) — same shape and same overall
 * structure as account-service's own handler of this name.
 *
 * <p>{@link BusinessRuleViolationException} is ONE handler for every 409/422 business rule.
 * Unlike account-service, this service's contract error table (data-model.md section 6) lists
 * only {@code OPERATION_ID_REUSED} at 409 — but {@code MongoUnitOfWorkAdapter} (R4) also raises
 * {@code BusinessRuleViolationException("CONCURRENT_MODIFICATION", ...)} when a transactional
 * retry is exhausted, exactly the same real conflict account-service's own ficha explicitly
 * puts at 409. Treating it as 422 instead just because this service's error table happens not
 * to spell it out would be inconsistent with the only other place in this whole codebase that
 * scenario exists, so {@link #CONFLICT_CODES} includes it too, same as account-service. That
 * constant is the one place this split is decided.
 *
 * <p>{@link MovementRejectedException}/{@link TransferRejectedException} are NOT domain
 * exceptions — see their own javadoc — but the translation happens the same way: a dedicated
 * handler builds the contract's operation-specific 422 body ({@code MovementRejected}/{@code
 * TransferRejected}, each {@code ErrorResponse} plus one or two extra fields). Those two
 * response schemas are inline {@code allOf} compositions under a named {@code
 * components.responses} entry rather than a top-level {@code components.schemas} entry, so
 * their generated class name is this file's one educated guess not yet confirmed against a real
 * build (same situation {@code AccountRestMapper} flags for its own inline-enum nesting) — if a
 * real build names them differently, this is the one spot to fix.
 *
 * <p>503 {@code SERVICE_UNAVAILABLE} is one handler for {@link
 * DownstreamServiceUnavailableException} — not thrown by anything yet (R7 builds the REST client
 * that would), declared now so the handler is ready; see that exception's own javadoc.
 *
 * <p>Path/query param validation and body validation handlers are copied verbatim from
 * account-service's own handler (same generator, same Spring Boot version, same {@code
 * @Validated}-on-the-generated-interface AOP mechanism confirmed there against a real stack
 * trace). {@link IllegalArgumentException} is additionally handled as a defensive 400: {@code
 * FindTransactionsUseCaseImpl} (R3) throws it when {@code customerId} is missing, a case the
 * generated interface's own required-query-param validation should already reject before this
 * controller is ever reached — this handler only matters if that assumption is ever wrong.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Set<String> CONFLICT_CODES = Set.of("OPERATION_ID_REUSED", "CONCURRENT_MODIFICATION");

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleAccountNotFound(AccountNotFoundException ex,
                                                                  ServerWebExchange exchange) {
        return build(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", ex.getMessage(), exchange, null);
    }

    @ExceptionHandler(TransactionNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleTransactionNotFound(TransactionNotFoundException ex,
                                                                      ServerWebExchange exchange) {
        return build(HttpStatus.NOT_FOUND, "TRANSACTION_NOT_FOUND", ex.getMessage(), exchange, null);
    }

    @ExceptionHandler(TransferNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleTransferNotFound(TransferNotFoundException ex,
                                                                   ServerWebExchange exchange) {
        return build(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND", ex.getMessage(), exchange, null);
    }

    @ExceptionHandler(DownstreamServiceUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleDownstreamUnavailable(DownstreamServiceUnavailableException ex,
                                                                        ServerWebExchange exchange) {
        return build(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", ex.getMessage(), exchange, null);
    }

    @ExceptionHandler(BusinessRuleViolationException.class)
    public ResponseEntity<ErrorResponse> handleBusinessRule(BusinessRuleViolationException ex,
                                                               ServerWebExchange exchange) {
        HttpStatus status = CONFLICT_CODES.contains(ex.getErrorCode())
                ? HttpStatus.CONFLICT
                : HttpStatus.UNPROCESSABLE_ENTITY;
        return build(status, ex.getErrorCode(), ex.getMessage(), exchange, null);
    }

    @ExceptionHandler(InvalidDateRangeException.class)
    public ResponseEntity<ErrorResponse> handleInvalidDateRange(InvalidDateRangeException ex,
                                                                   ServerWebExchange exchange) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE", ex.getMessage(), exchange, null);
    }

    @ExceptionHandler(MovementRejectedException.class)
    public ResponseEntity<com.bank.transaction.infrastructure.adapter.in.rest.dto.MovementRejected>
            handleMovementRejected(MovementRejectedException ex, ServerWebExchange exchange) {
        com.bank.transaction.domain.model.Transaction tx = ex.transaction();
        com.bank.transaction.infrastructure.adapter.in.rest.dto.MovementRejected body =
                new com.bank.transaction.infrastructure.adapter.in.rest.dto.MovementRejected();
        body.setTimestamp(OffsetDateTime.now());
        body.setStatus(HttpStatus.UNPROCESSABLE_ENTITY.value());
        body.setCode(tx.failureReason().code());
        body.setMessage(tx.failureReason().message());
        body.setPath(exchange.getRequest().getPath().value());
        body.setTransactionId(tx.id().value());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }

    @ExceptionHandler(TransferRejectedException.class)
    public ResponseEntity<com.bank.transaction.infrastructure.adapter.in.rest.dto.TransferRejected>
            handleTransferRejected(TransferRejectedException ex, ServerWebExchange exchange) {
        com.bank.transaction.domain.model.Transfer transfer = ex.transfer();
        com.bank.transaction.infrastructure.adapter.in.rest.dto.TransferRejected body =
                new com.bank.transaction.infrastructure.adapter.in.rest.dto.TransferRejected();
        body.setTimestamp(OffsetDateTime.now());
        body.setStatus(HttpStatus.UNPROCESSABLE_ENTITY.value());
        body.setCode(transfer.failureReason().code());
        body.setMessage(transfer.failureReason().message());
        body.setPath(exchange.getRequest().getPath().value());
        body.setTransferId(transfer.id().value());
        body.setTransferStatus(com.bank.transaction.infrastructure.adapter.in.rest.dto.TransferStatus
                .valueOf(transfer.status().name()));
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }

    /** Body validation failures: {@code @Valid @RequestBody Mono<X>} on every write endpoint. */
    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<ErrorResponse> handleBodyValidation(WebExchangeBindException ex,
                                                                 ServerWebExchange exchange) {
        List<FieldError> details = ex.getFieldErrors().stream()
                .map(fieldError -> {
                    FieldError detail = new FieldError();
                    detail.setField(fieldError.getField());
                    detail.setMessage(fieldError.getDefaultMessage());
                    return detail;
                })
                .collect(Collectors.toList());
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed", exchange, details);
    }

    /** Query/path param validation failures reported the newer (Spring 6.1+/Boot 3.5) way. */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleParamValidation(HandlerMethodValidationException ex,
                                                                  ServerWebExchange exchange) {
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", ex.getReason(), exchange, null);
    }

    /** Query/path param validation failures on a {@code @Validated}-annotated generated API
     *  interface (e.g. {@code @Size} on a path variable), thrown by {@code
     *  MethodValidationInterceptor} as this different exception type. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handlePathValidation(ConstraintViolationException ex,
                                                                 ServerWebExchange exchange) {
        List<FieldError> details = ex.getConstraintViolations().stream()
                .map(violation -> {
                    FieldError detail = new FieldError();
                    String path = violation.getPropertyPath().toString();
                    detail.setField(path.substring(path.lastIndexOf('.') + 1));
                    detail.setMessage(violation.getMessage());
                    return detail;
                })
                .collect(Collectors.toList());
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed", exchange, details);
    }

    /** Defensive only — see class javadoc. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex,
                                                                   ServerWebExchange exchange) {
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", ex.getMessage(), exchange, null);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String code, String message,
                                                   ServerWebExchange exchange, List<FieldError> details) {
        ErrorResponse body = new ErrorResponse();
        body.setTimestamp(OffsetDateTime.now());
        body.setStatus(status.value());
        body.setCode(code);
        body.setMessage(message);
        body.setPath(exchange.getRequest().getPath().value());
        body.setDetails(details);
        return ResponseEntity.status(status).body(body);
    }
}
