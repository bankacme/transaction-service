package com.bank.transaction.infrastructure.adapter.out.persistence;

import com.bank.transaction.application.port.out.UnitOfWorkPort;
import com.bank.transaction.domain.exception.BusinessRuleViolationException;
import com.bank.transaction.domain.model.Transaction;
import com.bank.transaction.domain.model.Transfer;
import com.bank.transaction.infrastructure.mapper.TransactionDocumentMapper;
import com.bank.transaction.infrastructure.mapper.TransferDocumentMapper;
import com.bank.transaction.infrastructure.support.RxJavaReactorBridge;
import com.mongodb.MongoException;
import io.reactivex.rxjava3.core.Single;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * Opens the MongoDB transaction a saga leg or a movement+fee pair needs for its two-collection
 * write — directly mirrors account-service's own {@code MongoUnitOfWorkAdapter}, including the
 * reasoning that earned it: this goes through {@link ReactiveMongoTemplate} directly, never
 * through {@link TransactionMongoRepository}/{@link TransferMongoRepository} (both {@code
 * RxJava3CrudRepository}-based), because a real run against a replica-set Mongo proved that
 * crossing from Reactor into RxJava3's own subscription mechanism and back loses Spring Data
 * Mongo's reactive session binding — that binding is looked up through Reactor's {@code
 * Context}, which a plain RxJava3 {@code Single} does not carry. Both writes only actually
 * share the transaction's session when they run inside ONE unbroken Reactor {@code Mono}
 * chain, built here with {@code mongoTemplate.save} directly and bridged to RxJava3 only once,
 * at the very end, via {@link RxJavaReactorBridge#toSingle(Mono)}.
 *
 * <p>Retries the whole two-write chain (a fresh {@code Mono} each attempt) up to 3 times on a
 * MongoDB-driver "TransientTransactionError" label ({@link MongoException#hasErrorLabel
 * (String)}), then gives up with {@code BusinessRuleViolationException("CONCURRENT_MODIFICATION"
 * , ...)}. A lost optimistic-lock race on {@link TransferDocument#getVersion()} surfaces as a
 * plain {@code OptimisticLockingFailureException} from {@code mongoTemplate.save} — not
 * silently retried or swallowed here, since the ficha does not specify a recovery rule for it;
 * {@link com.bank.transaction.application.usecase.RecoverPendingOperationsUseCaseImpl}'s
 * per-item error isolation (added alongside this adapter, R4) is what keeps that error from
 * crashing a whole recovery batch, while the synchronous {@code StartTransferUseCaseImpl} path
 * simply surfaces it to that one HTTP request.
 */
@Component
public class MongoUnitOfWorkAdapter implements UnitOfWorkPort {

    private static final int MAX_ATTEMPTS = 3;
    private static final String TRANSIENT_TRANSACTION_ERROR = "TransientTransactionError";

    private final ReactiveMongoTemplate mongoTemplate;
    private final TransactionalOperator transactionalOperator;
    private final TransactionDocumentMapper transactionMapper;
    private final TransferDocumentMapper transferMapper;

    public MongoUnitOfWorkAdapter(ReactiveMongoTemplate mongoTemplate, TransactionalOperator transactionalOperator,
                                   TransactionDocumentMapper transactionMapper,
                                   TransferDocumentMapper transferMapper) {
        this.mongoTemplate = mongoTemplate;
        this.transactionalOperator = transactionalOperator;
        this.transactionMapper = transactionMapper;
        this.transferMapper = transferMapper;
    }

    @Override
    public Single<Transfer> saveTransferAndTransaction(Transfer transfer, Transaction transaction) {
        return attemptTransferAndTransaction(transfer, transaction, MAX_ATTEMPTS);
    }

    @Override
    public Single<Transaction> saveTransactionAndFee(Transaction transaction, Transaction fee) {
        return attemptTransactionAndFee(transaction, fee, MAX_ATTEMPTS);
    }

    private Single<Transfer> attemptTransferAndTransaction(Transfer transfer, Transaction transaction,
                                                             int attemptsLeft) {
        Mono<Transfer> twoWriteChain = mongoTemplate.save(transferMapper.toDocument(transfer))
                .flatMap(savedTransferDocument -> mongoTemplate.save(transactionMapper.toDocument(transaction))
                        .thenReturn(transferMapper.toDomain(savedTransferDocument)));
        Mono<Transfer> transactional = transactionalOperator.transactional(twoWriteChain);
        return RxJavaReactorBridge.toSingle(transactional)
                .onErrorResumeNext(error -> {
                    if (!isTransientTransactionError(error)) {
                        return Single.error(error);
                    }
                    if (attemptsLeft > 1) {
                        return attemptTransferAndTransaction(transfer, transaction, attemptsLeft - 1);
                    }
                    return Single.error(giveUp());
                });
    }

    private Single<Transaction> attemptTransactionAndFee(Transaction transaction, Transaction fee,
                                                           int attemptsLeft) {
        Mono<Transaction> twoWriteChain = mongoTemplate.save(transactionMapper.toDocument(transaction))
                .flatMap(savedTransactionDocument -> mongoTemplate.save(transactionMapper.toDocument(fee))
                        .thenReturn(transactionMapper.toDomain(savedTransactionDocument)));
        Mono<Transaction> transactional = transactionalOperator.transactional(twoWriteChain);
        return RxJavaReactorBridge.toSingle(transactional)
                .onErrorResumeNext(error -> {
                    if (!isTransientTransactionError(error)) {
                        return Single.error(error);
                    }
                    if (attemptsLeft > 1) {
                        return attemptTransactionAndFee(transaction, fee, attemptsLeft - 1);
                    }
                    return Single.error(giveUp());
                });
    }

    private BusinessRuleViolationException giveUp() {
        return new BusinessRuleViolationException("CONCURRENT_MODIFICATION",
                "Gave up after " + MAX_ATTEMPTS + " attempts due to a write conflict");
    }

    private boolean isTransientTransactionError(Throwable error) {
        return error instanceof MongoException mongoError
                && mongoError.hasErrorLabel(TRANSIENT_TRANSACTION_ERROR);
    }

    /**
     * Test-only seam: the same first write (the {@code Transfer}, which does carry a real
     * {@code @Version} to violate), with an artificial failure forced right after it instead
     * of the real second write — mirrors account-service's own {@code
     * saveAccountForcingFailureAfterward}, proving the first write rolls back when the chain
     * fails before committing. Package-private: nothing in production has a legitimate reason
     * to force a failure mid-transaction, only {@code MongoUnitOfWorkAdapterTest} does.
     */
    Single<Transfer> saveTransferForcingFailureAfterward(Transfer transfer) {
        Mono<Transfer> chain = mongoTemplate.save(transferMapper.toDocument(transfer))
                .then(Mono.<Transfer>error(new IllegalStateException("forced failure for the rollback test")));
        Mono<Transfer> transactional = transactionalOperator.transactional(chain);
        return RxJavaReactorBridge.toSingle(transactional);
    }
}
