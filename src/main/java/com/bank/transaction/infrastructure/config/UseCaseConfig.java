package com.bank.transaction.infrastructure.config;

import com.bank.transaction.application.port.out.AccountLookupPort;
import com.bank.transaction.application.port.out.AccountMovementPort;
import com.bank.transaction.application.port.out.TransactionEventPublisherPort;
import com.bank.transaction.application.port.out.TransactionRepositoryPort;
import com.bank.transaction.application.port.out.TransferRepositoryPort;
import com.bank.transaction.application.port.out.UnitOfWorkPort;
import com.bank.transaction.application.usecase.DiscardTransactionUseCaseImpl;
import com.bank.transaction.application.usecase.FindProductTransactionsUseCaseImpl;
import com.bank.transaction.application.usecase.FindTransactionUseCaseImpl;
import com.bank.transaction.application.usecase.FindTransactionsUseCaseImpl;
import com.bank.transaction.application.usecase.FindTransferUseCaseImpl;
import com.bank.transaction.application.usecase.FindTransfersUseCaseImpl;
import com.bank.transaction.application.usecase.RecordExternalMovementUseCaseImpl;
import com.bank.transaction.application.usecase.RecoverPendingOperationsUseCaseImpl;
import com.bank.transaction.application.usecase.RegisterDepositUseCaseImpl;
import com.bank.transaction.application.usecase.RegisterWithdrawalUseCaseImpl;
import com.bank.transaction.application.usecase.StartTransferUseCaseImpl;
import com.bank.transaction.application.usecase.UpdateTransactionDescriptionUseCaseImpl;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the 12 R3 use cases as beans here, instead of annotating the *Impl classes with
 * {@code @Service} — the application layer stays framework-agnostic (no Spring import in it,
 * confirmed: none of the {@code application.usecase} package depends on Spring), same
 * principle as account-service's own {@code UseCaseConfig}, same reasoning in its javadoc.
 * Only this class (plus {@code ClockConfig}/{@code MongoConfig}, both R4) knows about Spring
 * on the application/domain side.
 *
 * <p>{@link AccountMovementPort}/{@link AccountLookupPort} resolve to the R6-only no-op
 * adapters ({@code infrastructure.adapter.out.noop}) until R7 replaces them with the real
 * REST client — see those adapters' own javadoc for why they fail loudly (503) instead of
 * pretending to work. {@link TransactionRepositoryPort}/{@link TransferRepositoryPort}/{@link
 * UnitOfWorkPort} resolve to the real Mongo adapters already built in R4.
 */
@Configuration
public class UseCaseConfig {

    @Bean
    public RegisterDepositUseCaseImpl registerDepositUseCase(TransactionRepositoryPort transactionRepositoryPort,
            AccountLookupPort accountLookupPort, AccountMovementPort accountMovementPort,
            UnitOfWorkPort unitOfWorkPort, TransactionEventPublisherPort eventPublisherPort, Clock clock) {
        return new RegisterDepositUseCaseImpl(transactionRepositoryPort, accountLookupPort, accountMovementPort,
                unitOfWorkPort, eventPublisherPort, clock);
    }

    @Bean
    public RegisterWithdrawalUseCaseImpl registerWithdrawalUseCase(TransactionRepositoryPort transactionRepositoryPort,
            AccountLookupPort accountLookupPort, AccountMovementPort accountMovementPort,
            UnitOfWorkPort unitOfWorkPort, TransactionEventPublisherPort eventPublisherPort, Clock clock) {
        return new RegisterWithdrawalUseCaseImpl(transactionRepositoryPort, accountLookupPort, accountMovementPort,
                unitOfWorkPort, eventPublisherPort, clock);
    }

    @Bean
    public StartTransferUseCaseImpl startTransferUseCase(TransferRepositoryPort transferRepositoryPort,
            AccountLookupPort accountLookupPort, AccountMovementPort accountMovementPort,
            UnitOfWorkPort unitOfWorkPort, TransactionEventPublisherPort eventPublisherPort, Clock clock) {
        return new StartTransferUseCaseImpl(transferRepositoryPort, accountLookupPort, accountMovementPort,
                unitOfWorkPort, eventPublisherPort, clock);
    }

    @Bean
    public FindTransferUseCaseImpl findTransferUseCase(TransferRepositoryPort repositoryPort) {
        return new FindTransferUseCaseImpl(repositoryPort);
    }

    @Bean
    public FindTransfersUseCaseImpl findTransfersUseCase(TransferRepositoryPort repositoryPort) {
        return new FindTransfersUseCaseImpl(repositoryPort);
    }

    @Bean
    public FindTransactionUseCaseImpl findTransactionUseCase(TransactionRepositoryPort repositoryPort) {
        return new FindTransactionUseCaseImpl(repositoryPort);
    }

    @Bean
    public FindTransactionsUseCaseImpl findTransactionsUseCase(TransactionRepositoryPort repositoryPort) {
        return new FindTransactionsUseCaseImpl(repositoryPort);
    }

    @Bean
    public FindProductTransactionsUseCaseImpl findProductTransactionsUseCase(
            TransactionRepositoryPort repositoryPort) {
        return new FindProductTransactionsUseCaseImpl(repositoryPort);
    }

    @Bean
    public UpdateTransactionDescriptionUseCaseImpl updateTransactionDescriptionUseCase(
            TransactionRepositoryPort repositoryPort, Clock clock) {
        return new UpdateTransactionDescriptionUseCaseImpl(repositoryPort, clock);
    }

    @Bean
    public DiscardTransactionUseCaseImpl discardTransactionUseCase(TransactionRepositoryPort repositoryPort,
                                                                      Clock clock) {
        return new DiscardTransactionUseCaseImpl(repositoryPort, clock);
    }

    @Bean
    public RecordExternalMovementUseCaseImpl recordExternalMovementUseCase(TransactionRepositoryPort repositoryPort,
            TransactionEventPublisherPort eventPublisherPort, Clock clock) {
        return new RecordExternalMovementUseCaseImpl(repositoryPort, eventPublisherPort, clock);
    }

    /** {@code defaultOlderThanMinutes}/{@code maxCompensationAttempts} come straight from the
     *  ficha's own proposed values (data-model.md section 8: {@code
     *  transaction.recovery.pending-minutes}=2, {@code transfer.compensation.max-attempts}=5)
     *  — real values once {@code bank-config} actually serves them; the {@code :2}/{@code :5}
     *  fallbacks only matter for a local run without a Config Server (and for the R4 {@code
     *  @SpringBootTest} suite, whose test {@code application.yml} repeats them explicitly
     *  anyway, same belt-and-suspenders reasoning as its {@code bank.zone}). */
    @Bean
    public RecoverPendingOperationsUseCaseImpl recoverPendingOperationsUseCase(
            TransactionRepositoryPort transactionRepositoryPort, TransferRepositoryPort transferRepositoryPort,
            AccountMovementPort accountMovementPort, UnitOfWorkPort unitOfWorkPort,
            TransactionEventPublisherPort eventPublisherPort,
            @Value("${transaction.recovery.pending-minutes:2}") int defaultOlderThanMinutes,
            @Value("${transfer.compensation.max-attempts:5}") int maxCompensationAttempts, Clock clock) {
        return new RecoverPendingOperationsUseCaseImpl(transactionRepositoryPort, transferRepositoryPort,
                accountMovementPort, unitOfWorkPort, eventPublisherPort, defaultOlderThanMinutes,
                maxCompensationAttempts, clock);
    }
}
