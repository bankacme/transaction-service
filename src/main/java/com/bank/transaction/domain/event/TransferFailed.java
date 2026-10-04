package com.bank.transaction.domain.event;

import com.bank.transaction.domain.model.Transfer;

/** Cubre Transfer en FAILED, COMPENSATED o COMPENSATION_FAILED (ficha, sección 3.6) — el
 *  {@code status} del evento distingue cuál de los tres fue. */
public record TransferFailed(String transferId, String status, String reasonCode) implements TransferDomainEvent {

    public static TransferFailed from(Transfer transfer) {
        return new TransferFailed(
                transfer.id().value(),
                transfer.status().name(),
                transfer.failureReason() == null ? null : transfer.failureReason().code());
    }
}
