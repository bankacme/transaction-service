package com.bank.transaction.infrastructure.adapter.out.noop;

import com.bank.transaction.application.port.out.TransactionEventPublisherPort;
import com.bank.transaction.domain.event.TransactionDomainEvent;
import com.bank.transaction.domain.event.TransferDomainEvent;
import io.reactivex.rxjava3.core.Completable;
import org.springframework.stereotype.Component;

/**
 * No-op for P1/P2 — {@link TransactionEventPublisherPort}'s own javadoc already says so (R3):
 * nothing consumes a transaction/transfer domain event yet, so publishing always completes.
 * Replaced by a Kafka producer in P3 (ficha 4.2). Same reasoning, same package name, as
 * account-service's {@code NoOpAccountEventPublisher} — unlike {@link NoOpAccountMovementAdapter}/
 * {@link NoOpAccountLookupAdapter} below, this one is a genuinely fine simplification to leave
 * in place through all of P1/P2, not a stand-in R7 is expected to replace right away.
 */
@Component
public class NoOpTransactionEventPublisher implements TransactionEventPublisherPort {

    @Override
    public Completable publish(TransactionDomainEvent event) {
        return Completable.complete();
    }

    @Override
    public Completable publish(TransferDomainEvent event) {
        return Completable.complete();
    }
}
