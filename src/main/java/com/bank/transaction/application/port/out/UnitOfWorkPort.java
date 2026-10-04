package com.bank.transaction.application.port.out;

import com.bank.transaction.domain.model.Transaction;
import com.bank.transaction.domain.model.Transfer;
import io.reactivex.rxjava3.core.Single;

/**
 * La ficha (sección 4.2) describe este puerto como un genérico {@code inTransaction(Single<T>)}.
 * Se cambia aquí a dos métodos concretos, por la misma razón que ya quedó documentada en
 * {@code account-service}'s {@code UnitOfWorkPort} (R3 de ese servicio): un {@code Single} de
 * RxJava3 no lleva el {@code Context} de Reactor, así que envolver en una transacción un
 * pipeline ya armado desde AFUERA no ata de verdad esas escrituras a la sesión transaccional
 * de Mongo — cada una confirma por su cuenta y una falla posterior no tiene nada que
 * deshacer. La única forma de que una transacción reactiva de MongoDB sostenga dos
 * escrituras juntas es que corran dentro de una sola cadena de Reactor sin RxJava3 en el
 * medio; nombrar exactamente lo que este puerto guarda en cada caso es lo que hace eso
 * posible para el adaptador (R4), en vez de aceptar un pipeline opaco que no se puede hacer
 * transaccional.
 *
 * <p>Dos combinaciones, igual que dice la ficha: {@code Transfer} + {@code Transaction} (una
 * pata de la saga) y un movimiento + su comisión {@code FEE}.
 */
public interface UnitOfWorkPort {

    Single<Transfer> saveTransferAndTransaction(Transfer transfer, Transaction transaction);

    Single<Transaction> saveTransactionAndFee(Transaction transaction, Transaction fee);
}
