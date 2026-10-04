package com.bank.transaction.application.usecase;

import com.bank.transaction.application.command.RegisterMovementCommand;
import com.bank.transaction.application.port.out.AccountLookupPort;
import com.bank.transaction.application.port.out.AccountMovementPort;
import com.bank.transaction.application.port.out.TransactionEventPublisherPort;
import com.bank.transaction.application.port.out.TransactionRepositoryPort;
import com.bank.transaction.application.port.out.UnitOfWorkPort;
import com.bank.transaction.domain.event.TransactionFailed;
import com.bank.transaction.domain.event.TransactionRegistered;
import com.bank.transaction.domain.exception.AccountNotFoundException;
import com.bank.transaction.domain.exception.BusinessRuleViolationException;
import com.bank.transaction.domain.model.AccountSnapshot;
import com.bank.transaction.domain.model.MovementOutcome;
import com.bank.transaction.domain.model.ProductRef;
import com.bank.transaction.domain.model.ProductType;
import com.bank.transaction.domain.model.Transaction;
import com.bank.transaction.domain.model.TransactionType;
import io.reactivex.rxjava3.core.Single;
import java.time.Clock;
import java.time.LocalDate;

/**
 * Compartido por RegisterDepositUseCaseImpl y RegisterWithdrawalUseCaseImpl: mismo flujo
 * (reglas 2 y 3), solo cambia el {@link TransactionType}. Idempotente por operationId: si ya
 * existe un Transaction con ese operationId y los mismos datos, se devuelve tal cual
 * (PENDING/COMPLETED/FAILED) sin volver a tocar la cuenta; con datos distintos, 409
 * OPERATION_ID_REUSED.
 */
abstract class AbstractRegisterMovementUseCase {

    private final TransactionType movementType;
    private final TransactionRepositoryPort transactionRepositoryPort;
    private final AccountLookupPort accountLookupPort;
    private final AccountMovementPort accountMovementPort;
    private final UnitOfWorkPort unitOfWorkPort;
    private final TransactionEventPublisherPort eventPublisherPort;
    private final Clock clock;

    protected AbstractRegisterMovementUseCase(TransactionType movementType,
            TransactionRepositoryPort transactionRepositoryPort, AccountLookupPort accountLookupPort,
            AccountMovementPort accountMovementPort, UnitOfWorkPort unitOfWorkPort,
            TransactionEventPublisherPort eventPublisherPort, Clock clock) {
        this.movementType = movementType;
        this.transactionRepositoryPort = transactionRepositoryPort;
        this.accountLookupPort = accountLookupPort;
        this.accountMovementPort = accountMovementPort;
        this.unitOfWorkPort = unitOfWorkPort;
        this.eventPublisherPort = eventPublisherPort;
        this.clock = clock;
    }

    protected Single<Transaction> doExecute(RegisterMovementCommand command) {
        ProductRef product = new ProductRef(command.accountId(), ProductType.ACCOUNT);
        return transactionRepositoryPort.findByOperationId(command.operationId())
                .flatMapSingle(existing -> replay(existing, product, command))
                .switchIfEmpty(Single.defer(() -> registerNew(command, product)));
    }

    private Single<Transaction> replay(Transaction existing, ProductRef product, RegisterMovementCommand command) {
        if (!existing.matches(product, movementType, command.amount())) {
            return Single.error(new BusinessRuleViolationException("OPERATION_ID_REUSED",
                    "operationId " + command.operationId().value() + " was already used for a different "
                            + "account/amount"));
        }
        return Single.just(existing);
    }

    private Single<Transaction> registerNew(RegisterMovementCommand command, ProductRef product) {
        return accountLookupPort.findById(command.accountId())
                .switchIfEmpty(Single.error(new AccountNotFoundException(command.accountId())))
                .flatMap(account -> createPendingAndApply(command, product, account));
    }

    private Single<Transaction> createPendingAndApply(RegisterMovementCommand command, ProductRef product,
                                                        AccountSnapshot account) {
        Transaction pending = Transaction.pending(command.operationId(), product, account.customerId(),
                movementType, command.amount(), null, null, null, command.description(), clock);
        return transactionRepositoryPort.save(pending).flatMap(saved -> requestMovement(saved, command));
    }

    private Single<Transaction> requestMovement(Transaction pending, RegisterMovementCommand command) {
        LocalDate today = LocalDate.now(clock);
        return accountMovementPort.apply(command.operationId(), command.accountId(), movementType, command.amount(),
                        today)
                .flatMap(outcome -> outcome.applied() ? complete(pending, outcome) : fail(pending, outcome));
    }

    private Single<Transaction> complete(Transaction pending, MovementOutcome outcome) {
        Transaction completed = pending.complete(outcome.resultingBalance(), clock);
        if (outcome.fee() == null || outcome.fee().isZero()) {
            return transactionRepositoryPort.save(completed)
                    .flatMap(saved -> eventPublisherPort.publish(TransactionRegistered.from(saved))
                            .andThen(Single.just(saved)));
        }
        return saveWithFee(completed, outcome);
    }

    private Single<Transaction> saveWithFee(Transaction completed, MovementOutcome outcome) {
        Transaction feeTx = Transaction.record(completed.operationId().forFee(), completed.product(),
                completed.customerId(), TransactionType.FEE, outcome.fee(), outcome.resultingBalance(),
                "Comision por " + movementType, clock);
        return unitOfWorkPort.saveTransactionAndFee(completed, feeTx)
                .flatMap(savedParent -> eventPublisherPort
                        .publish(TransactionRegistered.from(savedParent, outcome.fee().amount()))
                        .andThen(eventPublisherPort.publish(TransactionRegistered.from(feeTx)))
                        .andThen(Single.just(savedParent)));
    }

    private Single<Transaction> fail(Transaction pending, MovementOutcome outcome) {
        Transaction failed = pending.fail(outcome.failureReason(), clock);
        return transactionRepositoryPort.save(failed)
                .flatMap(saved -> eventPublisherPort.publish(TransactionFailed.from(saved))
                        .andThen(Single.just(saved)));
    }
}
