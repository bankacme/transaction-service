package com.bank.transaction.infrastructure.adapter.in.rest;

import com.bank.transaction.domain.model.Transfer;

/**
 * Same reasoning as {@link MovementRejectedException}, for {@code StartTransferUseCase} (R3):
 * a saga that ends {@code FAILED} (debit rejected) or {@code COMPENSATED} (credit rejected,
 * debit reversed) is still a normal, successfully-completed {@code Single<Transfer>} — nothing
 * exceptional happened in the use case. The contract's {@code TransferRejected} 422 body
 * (ErrorResponse + {@code transferId} + {@code transferStatus}) differs from the generated
 * interface's declared success type ({@code Transfer}), so {@code TransfersController} throws
 * this to reach {@link GlobalExceptionHandler#handleTransferRejected} instead.
 */
class TransferRejectedException extends RuntimeException {

    private final transient Transfer transfer;

    TransferRejectedException(Transfer transfer) {
        super("Transfer " + transfer.operationId().value() + " did not complete: " + transfer.status());
        this.transfer = transfer;
    }

    Transfer transfer() {
        return transfer;
    }
}
