package com.bank.transaction.domain.event;

import com.bank.transaction.domain.model.Money;
import com.bank.transaction.domain.model.Transaction;
import java.math.BigDecimal;
import java.time.Instant;

/** Se publica cuando la reversa SÍ tuvo éxito (después de {@code Transaction.markReversed}).
 *  El saldo resultante lo trae la respuesta de account-service (MovementOutcome), no el
 *  propio Transaction — éste conserva, sin tocar, el resultingBalance de cuando se completó
 *  originalmente. */
public record TransactionReversed(String operationId, String originalOperationId, String productId,
                                   BigDecimal amount, BigDecimal resultingBalance, Instant occurredAt)
        implements TransactionDomainEvent {

    public static TransactionReversed from(Transaction originalTransaction, Money resultingBalanceAfterReversal,
                                            Instant occurredAt) {
        return new TransactionReversed(
                originalTransaction.reversal().operationId().value(),
                originalTransaction.operationId().value(),
                originalTransaction.product().productId(),
                originalTransaction.amount().amount(),
                resultingBalanceAfterReversal.amount(),
                occurredAt);
    }
}
