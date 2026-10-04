package com.bank.transaction.domain.model;

import com.bank.transaction.domain.exception.InvalidTransitionException;
import java.time.Clock;
import java.time.Instant;

/**
 * Aggregate root: la máquina de estados de la saga de transferencia (ficha, sección 3.1).
 * Inmutable, mismo criterio que {@link Transaction}: cada transición valida que parte del
 * estado correcto y devuelve una nueva instancia. {@code version} se conserva sin tocar en
 * cada método: el control optimista es trabajo de Spring Data vía {@code @Version} sobre el
 * documento (R4), no del dominio.
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
public record Transfer(
        TransferId id,
        OperationId operationId,
        String sourceAccountId,
        String targetAccountId,
        Money amount,
        TransferKind kind,
        TransferStatus status,
        FailureReason failureReason,
        TransactionId debitTransactionId,
        TransactionId creditTransactionId,
        int compensationAttempts,
        String description,
        String sourceCustomerId,
        String targetCustomerId,
        String requestedBy,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public Transfer {
        if (id == null || operationId == null || sourceAccountId == null || sourceAccountId.isBlank()
                || targetAccountId == null || targetAccountId.isBlank() || amount == null || kind == null
                || status == null || sourceCustomerId == null || sourceCustomerId.isBlank()
                || targetCustomerId == null || targetCustomerId.isBlank() || requestedBy == null
                || requestedBy.isBlank() || createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("Required Transfer fields must not be null/blank");
        }
        if (compensationAttempts < 0) {
            throw new IllegalArgumentException("compensationAttempts must not be negative");
        }
    }

    /** TransferPolicy ya validó origen/destino/monto/estado de las cuentas (reglas 5 y 6) y
     *  calculó {@code kind}; aquí solo se abre la saga. */
    public static Transfer start(OperationId operationId, String sourceAccountId, String targetAccountId,
                                  Money amount, TransferKind kind, String description, String sourceCustomerId,
                                  String targetCustomerId, String requestedBy, Clock clock) {
        Instant now = clock.instant();
        return new Transfer(TransferId.newId(), operationId, sourceAccountId, targetAccountId, amount, kind,
                TransferStatus.STARTED, null, null, null, 0, description, sourceCustomerId, targetCustomerId,
                requestedBy, 0L, now, now);
    }

    /** STARTED -&gt; SOURCE_DEBITED: el retiro en origen se aplicó. */
    public Transfer sourceDebited(TransactionId newDebitTransactionId, Clock clock) {
        requireStatus(TransferStatus.STARTED);
        if (newDebitTransactionId == null) {
            throw new IllegalArgumentException("debitTransactionId is required");
        }
        return new Transfer(id, operationId, sourceAccountId, targetAccountId, amount, kind,
                TransferStatus.SOURCE_DEBITED, null, newDebitTransactionId, creditTransactionId,
                compensationAttempts, description, sourceCustomerId, targetCustomerId, requestedBy, version,
                createdAt, clock.instant());
    }

    /** SOURCE_DEBITED -&gt; COMPLETED: el depósito en destino también se aplicó. */
    public Transfer completed(TransactionId newCreditTransactionId, Clock clock) {
        requireStatus(TransferStatus.SOURCE_DEBITED);
        if (newCreditTransactionId == null) {
            throw new IllegalArgumentException("creditTransactionId is required");
        }
        return new Transfer(id, operationId, sourceAccountId, targetAccountId, amount, kind,
                TransferStatus.COMPLETED, null, debitTransactionId, newCreditTransactionId, compensationAttempts,
                description, sourceCustomerId, targetCustomerId, requestedBy, version, createdAt, clock.instant());
    }

    /** STARTED -&gt; FAILED: el retiro en origen fue rechazado; no hubo efecto que compensar. */
    public Transfer failed(FailureReason reason, Clock clock) {
        requireStatus(TransferStatus.STARTED);
        return new Transfer(id, operationId, sourceAccountId, targetAccountId, amount, kind, TransferStatus.FAILED,
                reason, debitTransactionId, creditTransactionId, compensationAttempts, description,
                sourceCustomerId, targetCustomerId, requestedBy, version, createdAt, clock.instant());
    }

    /** SOURCE_DEBITED -&gt; COMPENSATING: el depósito en destino fue rechazado, hay que
     *  revertir el retiro de origen. */
    public Transfer startCompensation(FailureReason reason, Clock clock) {
        requireStatus(TransferStatus.SOURCE_DEBITED);
        return new Transfer(id, operationId, sourceAccountId, targetAccountId, amount, kind,
                TransferStatus.COMPENSATING, reason, debitTransactionId, creditTransactionId, compensationAttempts,
                description, sourceCustomerId, targetCustomerId, requestedBy, version, createdAt, clock.instant());
    }

    /** Un intento de reversa falló pero todavía quedan reintentos (regla 9): se queda en
     *  COMPENSATING y solo sube el contador. No está en la lista original de la ficha, pero
     *  hace falta algo que lleve la cuenta antes de darse por vencido con
     *  {@link #compensationFailed}. */
    public Transfer compensationAttemptFailed(Clock clock) {
        requireStatus(TransferStatus.COMPENSATING);
        return new Transfer(id, operationId, sourceAccountId, targetAccountId, amount, kind, status, failureReason,
                debitTransactionId, creditTransactionId, compensationAttempts + 1, description, sourceCustomerId,
                targetCustomerId, requestedBy, version, createdAt, clock.instant());
    }

    /** COMPENSATING -&gt; COMPENSATED: la reversa del retiro de origen se aplicó. */
    public Transfer compensated(Clock clock) {
        requireStatus(TransferStatus.COMPENSATING);
        return new Transfer(id, operationId, sourceAccountId, targetAccountId, amount, kind,
                TransferStatus.COMPENSATED, failureReason, debitTransactionId, creditTransactionId,
                compensationAttempts, description, sourceCustomerId, targetCustomerId, requestedBy, version,
                createdAt, clock.instant());
    }

    /** COMPENSATING -&gt; COMPENSATION_FAILED: se agotaron los reintentos; queda para
     *  revisión manual (regla 9). */
    public Transfer compensationFailed(Clock clock) {
        requireStatus(TransferStatus.COMPENSATING);
        return new Transfer(id, operationId, sourceAccountId, targetAccountId, amount, kind,
                TransferStatus.COMPENSATION_FAILED, failureReason, debitTransactionId, creditTransactionId,
                compensationAttempts, description, sourceCustomerId, targetCustomerId, requestedBy, version,
                createdAt, clock.instant());
    }

    /** Mismo propósito que {@code Transaction.matches}, para la idempotencia de
     *  StartTransferUseCase (regla 2). */
    public boolean matches(String otherSourceAccountId, String otherTargetAccountId, Money otherAmount) {
        return sourceAccountId.equals(otherSourceAccountId) && targetAccountId.equals(otherTargetAccountId)
                && amount.equals(otherAmount);
    }

    private void requireStatus(TransferStatus expected) {
        if (status != expected) {
            throw new InvalidTransitionException(
                    "Transfer " + id.value() + " must be " + expected + " but is " + status);
        }
    }
}
