package com.bank.transaction.application.port.out;

import com.bank.transaction.domain.event.TransactionDomainEvent;
import com.bank.transaction.domain.event.TransferDomainEvent;
import io.reactivex.rxjava3.core.Completable;

/** No-op en P1/P2; publica a Kafka en P3 (ficha 4.2). Un solo puerto para los eventos de
 *  los dos aggregates, como lo nombra la ficha ("TransactionEventPublisherPort | publish(event)");
 *  dos sobrecargas porque {@code TransactionDomainEvent} y {@code TransferDomainEvent} son
 *  interfaces selladas distintas (R2). */
public interface TransactionEventPublisherPort {

    Completable publish(TransactionDomainEvent event);

    Completable publish(TransferDomainEvent event);
}
