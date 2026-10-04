package com.bank.transaction.domain.model;

/** Dato de lectura resuelto por AccountLookupPort (ficha, sección 4.2) — no es un aggregate
 *  de este servicio, solo lo que transaction-service necesita saber de la cuenta para
 *  validar un depósito/retiro/transferencia. */
public record AccountSnapshot(String accountId, String customerId, AccountType type, AccountStatus status) {

    public AccountSnapshot {
        if (accountId == null || accountId.isBlank() || customerId == null || customerId.isBlank()
                || type == null || status == null) {
            throw new IllegalArgumentException("All AccountSnapshot fields are required");
        }
    }
}
