package com.bank.transaction.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.transaction.application.command.RegisterMovementCommand;
import com.bank.transaction.application.usecase.TestAdapters.FakeAccountMovementPort;
import com.bank.transaction.application.usecase.TestAdapters.PassthroughUnitOfWorkPort;
import com.bank.transaction.application.usecase.TestAdapters.RecordingEventPublisherPort;
import com.bank.transaction.application.usecase.TestAdapters.StubAccountLookupPort;
import com.bank.transaction.domain.event.TransactionFailed;
import com.bank.transaction.domain.event.TransactionRegistered;
import com.bank.transaction.domain.exception.AccountNotFoundException;
import com.bank.transaction.domain.exception.BusinessRuleViolationException;
import com.bank.transaction.domain.model.AccountSnapshot;
import com.bank.transaction.domain.model.AccountStatus;
import com.bank.transaction.domain.model.AccountType;
import com.bank.transaction.domain.model.FailureReason;
import com.bank.transaction.domain.model.Money;
import com.bank.transaction.domain.model.MovementOutcome;
import com.bank.transaction.domain.model.OperationId;
import com.bank.transaction.domain.model.Transaction;
import com.bank.transaction.domain.model.TransactionStatus;
import com.bank.transaction.domain.model.TransactionType;
import io.reactivex.rxjava3.observers.TestObserver;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** Ejercita AbstractRegisterMovementUseCase de punta a punta a través del subtipo DEPOSIT;
 *  RegisterWithdrawalUseCaseImplTest solo confirma lo específico de WITHDRAWAL, para no
 *  duplicar toda esta batería. */
class RegisterDepositUseCaseImplTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-28T10:00:00Z"), ZoneOffset.UTC);
    private final InMemoryTransactionRepository transactionRepository = new InMemoryTransactionRepository();
    private final StubAccountLookupPort accountLookupPort = new StubAccountLookupPort()
            .with(new AccountSnapshot("acc-1", "cust-A", AccountType.SAVINGS, AccountStatus.ACTIVE));
    private final FakeAccountMovementPort accountMovementPort = new FakeAccountMovementPort();
    private final RecordingEventPublisherPort eventPublisherPort = new RecordingEventPublisherPort();
    private final RegisterDepositUseCaseImpl useCase = new RegisterDepositUseCaseImpl(transactionRepository,
            accountLookupPort, accountMovementPort,
            new PassthroughUnitOfWorkPort(new InMemoryTransferRepository(), transactionRepository),
            eventPublisherPort, clock);

    @Test
    void appliesADepositAndPublishesTransactionRegistered() {
        RegisterMovementCommand command = new RegisterMovementCommand(new OperationId("op-1"), "acc-1",
                Money.of(new BigDecimal("100.00")), "deposito");

        TestObserver<Transaction> observer = useCase.execute(command).test();

        observer.assertComplete();
        Transaction tx = observer.values().get(0);
        assertThat(tx.status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(tx.type()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(eventPublisherPort.transactionEvents()).hasSize(1);
        assertThat(eventPublisherPort.transactionEvents().get(0)).isInstanceOf(TransactionRegistered.class);
        assertThat(transactionRepository.size()).isEqualTo(1);
    }

    @Test
    void aDepositWithAFeePublishesTwoTransactionRegisteredEventsAndSavesBoth() {
        OperationId operationId = new OperationId("op-1");
        accountMovementPort.willApply(operationId.value(), MovementOutcome.applied(operationId,
                Money.of(new BigDecimal("1098.00")), Money.of(new BigDecimal("2.00"))));
        RegisterMovementCommand command = new RegisterMovementCommand(operationId, "acc-1",
                Money.of(new BigDecimal("100.00")), null);

        Transaction parent = useCase.execute(command).blockingGet();

        assertThat(parent.status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(transactionRepository.size()).isEqualTo(2); // parent + fee
        assertThat(eventPublisherPort.transactionEvents()).hasSize(2);
        TransactionRegistered parentEvent = (TransactionRegistered) eventPublisherPort.transactionEvents().get(0);
        assertThat(parentEvent.fee()).isEqualByComparingTo("2.00");
        TransactionRegistered feeEvent = (TransactionRegistered) eventPublisherPort.transactionEvents().get(1);
        assertThat(feeEvent.type()).isEqualTo(TransactionType.FEE.name());
        assertThat(feeEvent.fee()).isNull();
        // data-model 2.3: la comisión va enlazada al padre y comparte su instante.
        Transaction fee = transactionRepository.findByOperationId(new OperationId("op-1-FEE")).blockingGet();
        assertThat(fee.parentTransactionId()).isEqualTo(parent.id());
        assertThat(fee.occurredAt()).isEqualTo(parent.occurredAt());
        assertThat(fee.status()).isEqualTo(TransactionStatus.COMPLETED);
    }

    @Test
    void anUnknownAccountIsRejectedWithAccountNotFound() {
        RegisterMovementCommand command = new RegisterMovementCommand(new OperationId("op-1"), "acc-missing",
                Money.of(new BigDecimal("100.00")), null);

        useCase.execute(command).test().assertError(AccountNotFoundException.class);
    }

    @Test
    void aRejectedDepositIsPersistedAsFailedAndPublishesTransactionFailed() {
        OperationId operationId = new OperationId("op-1");
        accountMovementPort.willApply(operationId.value(),
                MovementOutcome.rejected(operationId, new FailureReason("ACCOUNT_INACTIVE", "cuenta inactiva")));
        RegisterMovementCommand command = new RegisterMovementCommand(operationId, "acc-1",
                Money.of(new BigDecimal("100.00")), null);

        Transaction tx = useCase.execute(command).blockingGet();

        assertThat(tx.status()).isEqualTo(TransactionStatus.FAILED);
        assertThat(tx.failureReason().code()).isEqualTo("ACCOUNT_INACTIVE");
        assertThat(eventPublisherPort.transactionEvents()).hasSize(1);
        assertThat(eventPublisherPort.transactionEvents().get(0)).isInstanceOf(TransactionFailed.class);
    }

    @Test
    void repeatingTheSameOperationIdReplaysTheResultWithoutApplyingTwice() {
        RegisterMovementCommand command = new RegisterMovementCommand(new OperationId("op-1"), "acc-1",
                Money.of(new BigDecimal("100.00")), null);

        Transaction first = useCase.execute(command).blockingGet();
        Transaction second = useCase.execute(command).blockingGet();

        assertThat(second).isEqualTo(first);
        assertThat(accountMovementPort.appliedOperationIds()).hasSize(1); // not applied twice
        assertThat(eventPublisherPort.transactionEvents()).hasSize(1); // not republished on replay
    }

    @Test
    void reusingAnOperationIdWithADifferentAmountIsRejected() {
        OperationId operationId = new OperationId("op-1");
        useCase.execute(new RegisterMovementCommand(operationId, "acc-1", Money.of(new BigDecimal("100.00")), null))
                .test().assertComplete();

        useCase.execute(new RegisterMovementCommand(operationId, "acc-1", Money.of(new BigDecimal("999.00")), null))
                .test()
                .assertError(error -> error instanceof BusinessRuleViolationException businessError
                        && "OPERATION_ID_REUSED".equals(businessError.getErrorCode()));
    }
}
