package com.bank.transaction.domain.event;

/** Eventos del aggregate {@code Transaction} (ficha, sección 3.6). Los de {@code Transfer}
 *  están en {@link TransferDomainEvent}: son aggregates distintos, cada uno con su propio
 *  ciclo de vida. */
public sealed interface TransactionDomainEvent
        permits TransactionRegistered, TransactionFailed, TransactionReversed, TransactionReversalFailed {
}
