package com.bank.transaction.application.port.in;

import com.bank.transaction.domain.model.DateRange;
import com.bank.transaction.domain.model.TransactionStatus;
import com.bank.transaction.domain.model.TransactionType;

/** GET /products/{productId}/transactions (contrato). Todos los campos opcionales — el
 *  productId lo recibe FindProductTransactionsUseCase aparte, no aquí. */
public record ProductTransactionFilter(DateRange range, TransactionType type, TransactionStatus status) {

    public static ProductTransactionFilter all() {
        return new ProductTransactionFilter(null, null, null);
    }
}
