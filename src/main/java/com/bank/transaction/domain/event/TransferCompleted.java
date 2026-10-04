package com.bank.transaction.domain.event;

import com.bank.transaction.domain.model.Transfer;
import java.math.BigDecimal;

public record TransferCompleted(String transferId, String sourceAccountId, String targetAccountId,
                                 BigDecimal amount, String kind) implements TransferDomainEvent {

    public static TransferCompleted from(Transfer transfer) {
        return new TransferCompleted(
                transfer.id().value(),
                transfer.sourceAccountId(),
                transfer.targetAccountId(),
                transfer.amount().amount(),
                transfer.kind().name());
    }
}
