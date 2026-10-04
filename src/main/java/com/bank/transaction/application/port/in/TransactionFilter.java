package com.bank.transaction.application.port.in;

import com.bank.transaction.domain.model.DateRange;
import com.bank.transaction.domain.model.TransactionStatus;
import com.bank.transaction.domain.model.TransactionType;

/** GET /transactions (contrato): consulta administrativa. {@code customerId} es
 *  obligatorio ahí (ficha, sección 5) — se valida en FindTransactionsUseCaseImpl, no aquí,
 *  para mantener este record simétrico a {@link ProductTransactionFilter}. */
public record TransactionFilter(String customerId, TransactionType type, TransactionStatus status, DateRange range) {
}
