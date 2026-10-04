package com.bank.transaction.infrastructure.scheduler;

import com.bank.transaction.application.port.in.RecoverPendingOperationsUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic sweep for operations stuck in a transient status (ficha §2.6 / data-model.md §8:
 * {@code transaction.recovery.cron}) — the first {@code @Scheduled} anywhere in this project
 * (checked: neither {@code account-service} nor {@code customer-service} has one), so there is
 * no sibling file to copy from; built directly against {@link RecoverPendingOperationsUseCase},
 * the R3 use case this whole mechanism already exists to drive.
 *
 * <p>Calls {@code execute(null)} on purpose — {@code null} means "use the configured default"
 * ({@code transaction.recovery.pending-minutes}, wired into {@code RecoverPendingOperationsUseCaseImpl}
 * in {@link com.bank.transaction.infrastructure.config.UseCaseConfig}), exactly the same default
 * the ficha's manual/on-demand sweep (via {@code InternalController.runTransactionRecovery}, R5)
 * falls back to when its own request body omits {@code olderThanMinutes}. The two entry points
 * share one policy; only the trigger differs (clock vs. an operator's `curl`).
 *
 * <p>{@code @EnableScheduling} (added to {@code TransactionServiceApplication} in this same R6)
 * is what actually makes Spring honor the {@code @Scheduled} annotation below — without it this
 * class would sit inert.
 */
@Component
public class TransactionRecoveryScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(TransactionRecoveryScheduler.class);

    private final RecoverPendingOperationsUseCase recoverPendingOperationsUseCase;

    public TransactionRecoveryScheduler(RecoverPendingOperationsUseCase recoverPendingOperationsUseCase) {
        this.recoverPendingOperationsUseCase = recoverPendingOperationsUseCase;
    }

    @Scheduled(cron = "${transaction.recovery.cron:0 * * * * *}")
    public void sweep() {
        recoverPendingOperationsUseCase.execute(null)
                .subscribe(
                        result -> LOG.info(
                                "Recovery sweep done: transactions retried={} completed={} failed={}, "
                                        + "transfers retried={} completed={} failed={}",
                                result.transactionsRetried(), result.transactionsCompleted(),
                                result.transactionsFailed(), result.transfersRetried(),
                                result.transfersCompleted(), result.transfersFailed()),
                        error -> LOG.error("Recovery sweep failed", error));
    }
}
