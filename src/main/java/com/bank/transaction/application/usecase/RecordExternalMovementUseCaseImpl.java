package com.bank.transaction.application.usecase;

import com.bank.transaction.application.command.RecordExternalMovementCommand;
import com.bank.transaction.application.port.in.RecordExternalMovementUseCase;
import com.bank.transaction.application.port.out.TransactionEventPublisherPort;
import com.bank.transaction.application.port.out.TransactionRepositoryPort;
import com.bank.transaction.domain.event.TransactionRegistered;
import com.bank.transaction.domain.exception.BusinessRuleViolationException;
import com.bank.transaction.domain.model.Transaction;
import io.reactivex.rxjava3.core.Single;
import java.time.Clock;

/** Regla 12: credit-service ya aplicó el efecto, aquí solo se registra. Idempotente por
 *  operationId: un duplicado con los mismos datos devuelve el registro ya guardado; con
 *  datos distintos, 409 OPERATION_ID_REUSED (mismo criterio que los movimientos propios). */
public class RecordExternalMovementUseCaseImpl implements RecordExternalMovementUseCase {

    private final TransactionRepositoryPort repositoryPort;
    private final TransactionEventPublisherPort eventPublisherPort;
    private final Clock clock;

    public RecordExternalMovementUseCaseImpl(TransactionRepositoryPort repositoryPort,
                                              TransactionEventPublisherPort eventPublisherPort, Clock clock) {
        this.repositoryPort = repositoryPort;
        this.eventPublisherPort = eventPublisherPort;
        this.clock = clock;
    }

    @Override
    public Single<Transaction> execute(RecordExternalMovementCommand command) {
        return repositoryPort.findByOperationId(command.operationId())
                .flatMapSingle(existing -> replay(existing, command))
                .switchIfEmpty(Single.defer(() -> recordNew(command)));
    }

    private Single<Transaction> replay(Transaction existing, RecordExternalMovementCommand command) {
        if (!existing.matches(command.product(), command.type(), command.amount())) {
            return Single.error(new BusinessRuleViolationException("OPERATION_ID_REUSED",
                    "operationId " + command.operationId().value() + " was already used for a different movement"));
        }
        return Single.just(existing);
    }

    private Single<Transaction> recordNew(RecordExternalMovementCommand command) {
        Transaction recorded = Transaction.record(command.operationId(), command.product(), command.customerId(),
                command.type(), command.amount(), command.resultingBalance(), command.description(), clock);
        return repositoryPort.save(recorded)
                .flatMap(saved -> eventPublisherPort.publish(TransactionRegistered.from(saved))
                        .andThen(Single.just(saved)));
    }
}
