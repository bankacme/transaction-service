package com.bank.transaction.domain.exception;

public class TransferNotFoundException extends RuntimeException {

    public TransferNotFoundException(String transferId) {
        super("Transfer not found: " + transferId);
    }
}
