package com.bank.transaction.domain.event;

public sealed interface TransferDomainEvent permits TransferCompleted, TransferFailed {
}
