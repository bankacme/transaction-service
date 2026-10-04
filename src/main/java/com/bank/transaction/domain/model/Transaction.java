package com.bank.transaction.domain.model;

import com.bank.transaction.domain.exception.BusinessRuleViolationException;
import com.bank.transaction.domain.exception.InvalidTransitionException;
import java.time.Clock;
import java.time.Instant;

/**
 * Aggregate root. Inmutable: cada método de comportamiento devuelve una nueva instancia
 * (mismo criterio que {@code Account} en account-service). Un {@code Transaction} es UN
 * movimiento sobre UN solo producto: depósito, retiro, pata de transferencia, comisión, o el
 * registro de un pago/consumo de crédito o tarjeta (ficha, sección 3.1). Es el registro del
 * historial.
 *
 * <p>Las transiciones de estado inválidas (llamar {@code complete} sobre algo que no está
 * {@code PENDING}, por ejemplo) son un error defensivo del caso de uso y lanzan
 * {@link InvalidTransitionException}; los rechazos que el cliente puede provocar de verdad
 * (monto inválido, tipo que no corresponde al producto, descartar algo que no es
 * {@code FAILED}...) lanzan {@link BusinessRuleViolationException} con el código del
 * contrato.
 */
public record Transaction(
        TransactionId id,
        OperationId operationId,
        ProductRef product,
        String customerId,
        TransactionType type,
        Money amount,
        Money resultingBalance,
        TransactionStatus status,
        FailureReason failureReason,
        TransferId transferId,
        TransactionId parentTransactionId,
        String payerCustomerId,
        String description,
        TransactionReversal reversal,
        Instant occurredAt,
        Instant createdAt,
        Instant updatedAt) {

    private static final int MAX_DESCRIPTION_LENGTH = 140;

    public Transaction {
        if (id == null || operationId == null || product == null || customerId == null || customerId.isBlank()
                || type == null || amount == null || status == null || occurredAt == null || createdAt == null
                || updatedAt == null) {
            throw new IllegalArgumentException("Required Transaction fields must not be null/blank");
        }
        description = normalizeDescription(description);
    }

    /** Nace a la espera de la respuesta de account-service: depósito, retiro o pata de una
     *  transferencia (regla 3). Regla 1: el monto debe ser mayor a cero. */
    public static Transaction pending(OperationId operationId, ProductRef product, String customerId,
                                       TransactionType type, Money amount, TransferId transferId,
                                       TransactionId parentTransactionId, String payerCustomerId,
                                       String description, Clock clock) {
        requirePositiveAmount(amount);
        requireValidTypeForProduct(product.productType(), type);
        Instant now = clock.instant();
        return new Transaction(TransactionId.newId(), operationId, product, customerId, type, amount, null,
                TransactionStatus.PENDING, null, transferId, parentTransactionId, payerCustomerId, description,
                null, now, now, now);
    }

    /** Regla 12: un movimiento de crédito/tarjeta se registra ya ocurrido (el efecto pasó en
     *  credit-service), sin pasar por PENDING. */
    public static Transaction record(OperationId operationId, ProductRef product, String customerId,
                                      TransactionType type, Money amount, Money resultingBalance,
                                      String description, Clock clock) {
        requirePositiveAmount(amount);
        requireValidTypeForProduct(product.productType(), type);
        if (resultingBalance == null) {
            throw new IllegalArgumentException("resultingBalance is required for an already-applied movement");
        }
        Instant now = clock.instant();
        return new Transaction(TransactionId.newId(), operationId, product, customerId, type, amount,
                resultingBalance, TransactionStatus.COMPLETED, null, null, null, null, description, null, now, now,
                now);
    }

    /** PENDING -&gt; COMPLETED, con el saldo que informó account-service. */
    public Transaction complete(Money resultingBalance, Clock clock) {
        requirePending();
        if (resultingBalance == null) {
            throw new IllegalArgumentException("resultingBalance is required to complete a transaction");
        }
        return new Transaction(id, operationId, product, customerId, type, amount, resultingBalance,
                TransactionStatus.COMPLETED, null, transferId, parentTransactionId, payerCustomerId, description,
                reversal, occurredAt, createdAt, clock.instant());
    }

    /** PENDING -&gt; FAILED. El motivo se conserva (regla 3). */
    public Transaction fail(FailureReason reason, Clock clock) {
        requirePending();
        if (reason == null) {
            throw new IllegalArgumentException("reason is required to fail a transaction");
        }
        return new Transaction(id, operationId, product, customerId, type, amount, null, TransactionStatus.FAILED,
                reason, transferId, parentTransactionId, payerCustomerId, description, reversal, occurredAt,
                createdAt, clock.instant());
    }

    /** COMPLETED -&gt; REVERSED. Solo para una reversa exitosa; para una rechazada usa
     *  {@link #markReversalFailed}, que no cambia el estado. */
    public Transaction markReversed(TransactionReversal reversalAttempt, Clock clock) {
        requireCompleted();
        if (reversalAttempt == null || reversalAttempt.outcome() != ReversalOutcome.REVERSED) {
            throw new IllegalArgumentException("markReversed requires a successful TransactionReversal");
        }
        return new Transaction(id, operationId, product, customerId, type, amount, resultingBalance,
                TransactionStatus.REVERSED, failureReason, transferId, parentTransactionId, payerCustomerId,
                description, reversalAttempt, occurredAt, createdAt, clock.instant());
    }

    /** La cuenta rechazó la reversa: el movimiento original sigue COMPLETED (se puede
     *  reintentar), pero queda anotado el intento fallido para trazabilidad. */
    public Transaction markReversalFailed(TransactionReversal reversalAttempt, Clock clock) {
        requireCompleted();
        if (reversalAttempt == null || reversalAttempt.outcome() != ReversalOutcome.REVERSAL_FAILED) {
            throw new IllegalArgumentException("markReversalFailed requires a rejected TransactionReversal");
        }
        return new Transaction(id, operationId, product, customerId, type, amount, resultingBalance, status,
                failureReason, transferId, parentTransactionId, payerCustomerId, description, reversalAttempt,
                occurredAt, createdAt, clock.instant());
    }

    /** Regla 13: lo único editable del historial. No aplica sobre un movimiento descartado. */
    public Transaction updateDescription(String newDescription, Clock clock) {
        requireNotDiscarded();
        return new Transaction(id, operationId, product, customerId, type, amount, resultingBalance, status,
                failureReason, transferId, parentTransactionId, payerCustomerId,
                normalizeDescription(newDescription), reversal, occurredAt, createdAt, clock.instant());
    }

    /** Baja lógica (regla 13): solo un movimiento FAILED se puede descartar. */
    public Transaction discard(Clock clock) {
        if (status != TransactionStatus.FAILED) {
            throw new BusinessRuleViolationException("NOT_DISCARDABLE",
                    "Transaction " + id.value() + " is " + status + ", only a FAILED transaction can be discarded");
        }
        return new Transaction(id, operationId, product, customerId, type, amount, resultingBalance,
                TransactionStatus.DISCARDED, failureReason, transferId, parentTransactionId, payerCustomerId,
                description, reversal, occurredAt, createdAt, clock.instant());
    }

    /** Para la idempotencia (regla 2): si ya existe un Transaction con este operationId,
     *  esto decide si la petición es un reintento legítimo (mismos datos, se repite el
     *  resultado) o reutiliza el operationId con datos distintos (409 OPERATION_ID_REUSED,
     *  responsabilidad del caso de uso que llama a este método). */
    public boolean matches(ProductRef otherProduct, TransactionType otherType, Money otherAmount) {
        return product.equals(otherProduct) && type == otherType && amount.equals(otherAmount);
    }

    private void requirePending() {
        if (status != TransactionStatus.PENDING) {
            throw new InvalidTransitionException(
                    "Transaction " + id.value() + " must be PENDING but is " + status);
        }
    }

    private void requireCompleted() {
        if (status != TransactionStatus.COMPLETED) {
            throw new InvalidTransitionException(
                    "Transaction " + id.value() + " must be COMPLETED but is " + status);
        }
    }

    private void requireNotDiscarded() {
        if (status == TransactionStatus.DISCARDED) {
            throw new BusinessRuleViolationException("TRANSACTION_DISCARDED",
                    "Transaction " + id.value() + " was discarded and can no longer be edited");
        }
    }

    private static void requirePositiveAmount(Money amount) {
        if (amount.isZero()) {
            throw new BusinessRuleViolationException("INVALID_AMOUNT", "Amount must be greater than zero");
        }
    }

    /** Mapeo producto/tipo asumido por nombre — la ficha no lo detalla explícitamente.
     *  ACCOUNT admite los movimientos de cuenta; CREDIT solo pagos de crédito; CREDIT_CARD
     *  solo consumos/pagos de tarjeta. Ajusta este switch si decides otra combinación. */
    private static void requireValidTypeForProduct(ProductType productType, TransactionType type) {
        boolean valid = switch (productType) {
            case ACCOUNT -> type == TransactionType.DEPOSIT || type == TransactionType.WITHDRAWAL
                    || type == TransactionType.TRANSFER_OUT || type == TransactionType.TRANSFER_IN
                    || type == TransactionType.FEE || type == TransactionType.DEBIT_PAYMENT
                    || type == TransactionType.YANKI_PAYMENT_OUT || type == TransactionType.YANKI_PAYMENT_IN;
            case CREDIT -> type == TransactionType.CREDIT_PAYMENT;
            case CREDIT_CARD -> type == TransactionType.CARD_PAYMENT || type == TransactionType.CARD_CHARGE;
        };
        if (!valid) {
            throw new BusinessRuleViolationException("INVALID_TYPE_FOR_PRODUCT",
                    "Transaction type " + type + " is not valid for product type " + productType);
        }
    }

    private static String normalizeDescription(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        String trimmed = description.trim();
        if (trimmed.length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException(
                    "Description must be at most " + MAX_DESCRIPTION_LENGTH + " characters");
        }
        return trimmed;
    }
}
