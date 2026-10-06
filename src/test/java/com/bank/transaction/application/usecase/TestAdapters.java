package com.bank.transaction.application.usecase;

import com.bank.transaction.application.port.out.AccountLookupPort;
import com.bank.transaction.application.port.out.AccountMovementPort;
import com.bank.transaction.application.port.out.TransactionEventPublisherPort;
import com.bank.transaction.application.port.out.TransactionRepositoryPort;
import com.bank.transaction.application.port.out.TransferRepositoryPort;
import com.bank.transaction.application.port.out.UnitOfWorkPort;
import com.bank.transaction.domain.event.TransactionDomainEvent;
import com.bank.transaction.domain.event.TransferDomainEvent;
import com.bank.transaction.domain.model.AccountSnapshot;
import com.bank.transaction.domain.model.Money;
import com.bank.transaction.domain.model.MovementOutcome;
import com.bank.transaction.domain.model.OperationId;
import com.bank.transaction.domain.model.Transaction;
import com.bank.transaction.domain.model.TransactionType;
import com.bank.transaction.domain.model.Transfer;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** One-line stub/fake adapters for the out-ports a given use-case test needs to configure or
 *  assert against. Grouped in one file since most are a handful of lines — split one out if a
 *  test needs to override just its behavior. */
public final class TestAdapters {

    private TestAdapters() {
    }

    /** Answers whatever AccountSnapshot was registered for an accountId (or empty, i.e. 404). */
    public static final class StubAccountLookupPort implements AccountLookupPort {
        private final Map<String, AccountSnapshot> byAccountId = new HashMap<>();

        public StubAccountLookupPort with(AccountSnapshot snapshot) {
            byAccountId.put(snapshot.accountId(), snapshot);
            return this;
        }

        @Override
        public Maybe<AccountSnapshot> findById(String accountId) {
            AccountSnapshot found = byAccountId.get(accountId);
            return found == null ? Maybe.empty() : Maybe.just(found);
        }
    }

    /** Scripted stand-in for the REST+circuit-breaker call to account-service: defaults to
     *  applying/reversing successfully with a fixed resulting balance, but a test can program
     *  an explicit {@link MovementOutcome} for any given operationId (the derived -OUT/-IN/-REV
     *  ones included). Records every call so a test can assert a movement was (or was not)
     *  requested twice — that's the whole point of the idempotency tests. */
    public static final class FakeAccountMovementPort implements AccountMovementPort {
        private static final Money DEFAULT_BALANCE = Money.of(new BigDecimal("1000.00"));

        private final List<String> appliedOperationIds = new ArrayList<>();
        private final List<String> reversedOperationIds = new ArrayList<>();
        private final Map<String, MovementOutcome> applyOutcomes = new HashMap<>();
        private final Map<String, MovementOutcome> reverseOutcomes = new HashMap<>();
        private final Map<String, RuntimeException> applyErrors = new HashMap<>();
        private final Map<String, RuntimeException> reverseErrors = new HashMap<>();

        public FakeAccountMovementPort willApply(String operationIdValue, MovementOutcome outcome) {
            applyOutcomes.put(operationIdValue, outcome);
            return this;
        }

        public FakeAccountMovementPort willReverse(String operationIdValue, MovementOutcome outcome) {
            reverseOutcomes.put(operationIdValue, outcome);
            return this;
        }

        /** Simulates an infrastructure failure (a timeout, a dropped connection) rather than a
         *  business rejection — {@code apply()} errors instead of resolving with a {@link
         *  MovementOutcome}. Used to prove per-item error isolation in {@code
         *  RecoverPendingOperationsUseCaseImpl}: this one operationId blows up, the rest of the
         *  batch must still go through. */
        public FakeAccountMovementPort willApplyError(String operationIdValue, RuntimeException error) {
            applyErrors.put(operationIdValue, error);
            return this;
        }

        public FakeAccountMovementPort willReverseError(String operationIdValue, RuntimeException error) {
            reverseErrors.put(operationIdValue, error);
            return this;
        }

        @Override
        public Single<MovementOutcome> apply(OperationId operationId, String accountId, TransactionType type,
                                              Money amount, LocalDate date) {
            appliedOperationIds.add(operationId.value());
            RuntimeException error = applyErrors.get(operationId.value());
            if (error != null) {
                return Single.error(error);
            }
            MovementOutcome configured = applyOutcomes.get(operationId.value());
            return Single.just(configured != null ? configured
                    : MovementOutcome.applied(operationId, DEFAULT_BALANCE, null));
        }

        @Override
        public Single<MovementOutcome> reverse(OperationId operationId, String accountId) {
            reversedOperationIds.add(operationId.value());
            RuntimeException error = reverseErrors.get(operationId.value());
            if (error != null) {
                return Single.error(error);
            }
            MovementOutcome configured = reverseOutcomes.get(operationId.value());
            return Single.just(configured != null ? configured
                    : MovementOutcome.applied(operationId, DEFAULT_BALANCE, null));
        }

        public List<String> appliedOperationIds() {
            return appliedOperationIds;
        }

        public List<String> reversedOperationIds() {
            return reversedOperationIds;
        }
    }

    /** Records every event published, in order, so a test can assert on them. One list per
     *  sealed interface, since TransactionEventPublisherPort overloads publish() for each. */
    public static final class RecordingEventPublisherPort implements TransactionEventPublisherPort {
        private final List<TransactionDomainEvent> transactionEvents = new ArrayList<>();
        private final List<TransferDomainEvent> transferEvents = new ArrayList<>();

        @Override
        public Completable publish(TransactionDomainEvent event) {
            transactionEvents.add(event);
            return Completable.complete();
        }

        @Override
        public Completable publish(TransferDomainEvent event) {
            transferEvents.add(event);
            return Completable.complete();
        }

        public List<TransactionDomainEvent> transactionEvents() {
            return transactionEvents;
        }

        public List<TransferDomainEvent> transferEvents() {
            return transferEvents;
        }
    }

    /** P1/P2 has no real Mongo transaction to open (see UnitOfWorkPort's Javadoc on why), so
     *  this just delegates straight to the same in-memory repositories the test itself uses —
     *  no real transaction semantics here, since these fakes have no concept of one; that's
     *  exactly what a MongoUnitOfWorkAdapterTest (R4, against a real Mongo) would verify for
     *  real. */
    public static final class PassthroughUnitOfWorkPort implements UnitOfWorkPort {
        private final TransferRepositoryPort transferRepositoryPort;
        private final TransactionRepositoryPort transactionRepositoryPort;

        public PassthroughUnitOfWorkPort(TransferRepositoryPort transferRepositoryPort,
                                          TransactionRepositoryPort transactionRepositoryPort) {
            this.transferRepositoryPort = transferRepositoryPort;
            this.transactionRepositoryPort = transactionRepositoryPort;
        }

        @Override
        public Single<Transfer> saveTransferAndTransactions(Transfer transfer, List<Transaction> transactions) {
            return Flowable.fromIterable(transactions)
                    .concatMapSingle(transactionRepositoryPort::save)
                    .ignoreElements()
                    .andThen(Single.defer(() -> transferRepositoryPort.save(transfer)));
        }

        @Override
        public Single<Transaction> saveTransactionAndFee(Transaction transaction, Transaction fee) {
            return transactionRepositoryPort.save(fee).flatMap(savedFee -> transactionRepositoryPort.save(transaction));
        }
    }
}
