package com.bank.transaction.domain.event;

import com.bank.transaction.domain.model.Transaction;
import java.math.BigDecimal;
import java.time.Instant;

/** Se publica cuando un movimiento queda COMPLETED — también para los FEE que quedan
 *  enlazados a un movimiento padre (ficha, sección 3.6). */
public record TransactionRegistered(
        String transactionId, String operationId, String productId, String productType, String customerId,
        String type, BigDecimal amount, BigDecimal fee, BigDecimal resultingBalance, String description,
        String transferId, String parentTransactionId, String payerCustomerId, Instant occurredAt)
        implements TransactionDomainEvent {

    /** Para un movimiento sin comisión enlazada; para publicar también el FEE usa
     *  {@link #from(Transaction, BigDecimal)}. */
    public static TransactionRegistered from(Transaction transaction) {
        return from(transaction, null);
    }

    public static TransactionRegistered from(Transaction transaction, BigDecimal fee) {
        return new TransactionRegistered(
                transaction.id().value(),
                transaction.operationId().value(),
                transaction.product().productId(),
                transaction.product().productType().name(),
                transaction.customerId(),
                transaction.type().name(),
                transaction.amount().amount(),
                fee,
                transaction.resultingBalance() == null ? null : transaction.resultingBalance().amount(),
                transaction.description(),
                transaction.transferId() == null ? null : transaction.transferId().value(),
                transaction.parentTransactionId() == null ? null : transaction.parentTransactionId().value(),
                transaction.payerCustomerId(),
                transaction.occurredAt());
    }
}
