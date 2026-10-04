package com.bank.transaction.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.transaction.application.command.RegisterMovementCommand;
import com.bank.transaction.application.usecase.TestAdapters.FakeAccountMovementPort;
import com.bank.transaction.application.usecase.TestAdapters.PassthroughUnitOfWorkPort;
import com.bank.transaction.application.usecase.TestAdapters.RecordingEventPublisherPort;
import com.bank.transaction.application.usecase.TestAdapters.StubAccountLookupPort;
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
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** El resto del flujo (comisión, idempotencia, 404, evento de rechazo) ya está cubierto a
 *  fondo en RegisterDepositUseCaseImplTest contra la misma AbstractRegisterMovementUseCase;
 *  esta clase solo confirma lo que es específico de WITHDRAWAL. */
class RegisterWithdrawalUseCaseImplTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-28T10:00:00Z"), ZoneOffset.UTC);
    private final InMemoryTransactionRepository transactionRepository = new InMemoryTransactionRepository();
    private final StubAccountLookupPort accountLookupPort = new StubAccountLookupPort()
            .with(new AccountSnapshot("acc-1", "cust-A", AccountType.SAVINGS, AccountStatus.ACTIVE));
    private final FakeAccountMovementPort accountMovementPort = new FakeAccountMovementPort();
    private final RecordingEventPublisherPort eventPublisherPort = new RecordingEventPublisherPort();
    private final RegisterWithdrawalUseCaseImpl useCase = new RegisterWithdrawalUseCaseImpl(transactionRepository,
            accountLookupPort, accountMovementPort,
            new PassthroughUnitOfWorkPort(new InMemoryTransferRepository(), transactionRepository),
            eventPublisherPort, clock);

    @Test
    void appliesAWithdrawalAsTheWithdrawalType() {
        RegisterMovementCommand command = new RegisterMovementCommand(new OperationId("op-1"), "acc-1",
                Money.of(new BigDecimal("50.00")), "retiro cajero");

        Transaction tx = useCase.execute(command).blockingGet();

        assertThat(tx.type()).isEqualTo(TransactionType.WITHDRAWAL);
        assertThat(tx.status()).isEqualTo(TransactionStatus.COMPLETED);
    }

    @Test
    void insufficientFundsRejectsTheWithdrawalAndKeepsTheReason() {
        OperationId operationId = new OperationId("op-1");
        accountMovementPort.willApply(operationId.value(), MovementOutcome.rejected(operationId,
                new FailureReason("INSUFFICIENT_FUNDS", "no alcanza el saldo")));
        RegisterMovementCommand command = new RegisterMovementCommand(operationId, "acc-1",
                Money.of(new BigDecimal("5000.00")), null);

        Transaction tx = useCase.execute(command).blockingGet();

        assertThat(tx.status()).isEqualTo(TransactionStatus.FAILED);
        assertThat(tx.failureReason().code()).isEqualTo("INSUFFICIENT_FUNDS");
    }
}
