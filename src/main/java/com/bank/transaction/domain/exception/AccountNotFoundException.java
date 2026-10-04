package com.bank.transaction.domain.exception;

/** La cuenta no existe según AccountLookupPort (404). Distinta de TransactionNotFoundException
 *  / TransferNotFoundException: esta es sobre un producto de account-service, no sobre un
 *  aggregate propio. */
public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException(String accountId) {
        super("Account not found: " + accountId);
    }
}
