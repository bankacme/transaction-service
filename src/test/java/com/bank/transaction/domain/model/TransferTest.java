package com.bank.transaction.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.transaction.domain.exception.InvalidTransitionException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class TransferTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-01T10:00:00Z"), ZoneOffset.UTC);
    private final Money amount = Money.of(new BigDecimal("500.00"));

    private Transfer started() {
        return Transfer.start(new OperationId("tr-1"), "acc-A", "acc-B", amount, TransferKind.OWN, "ahorro",
                "cust-A", "cust-A", "cust-A", clock);
    }

    // --- start() --------------------------------------------------------------------------

    @Test
    void startCreatesATransferInStartedStatus() {
        Transfer transfer = started();

        assertThat(transfer.status()).isEqualTo(TransferStatus.STARTED);
        assertThat(transfer.compensationAttempts()).isZero();
        assertThat(transfer.debitTransactionId()).isNull();
    }

    // --- sourceDebited() / completed() -----------------------------------------------------

    @Test
    void sourceDebitedMovesStartedToSourceDebited() {
        TransactionId debitTxId = TransactionId.newId();
        Transfer transfer = started().sourceDebited(debitTxId, clock);

        assertThat(transfer.status()).isEqualTo(TransferStatus.SOURCE_DEBITED);
        assertThat(transfer.debitTransactionId()).isEqualTo(debitTxId);
    }

    @Test
    void sourceDebitedRejectsATransferThatIsNotStarted() {
        Transfer sourceDebited = started().sourceDebited(TransactionId.newId(), clock);

        assertThatThrownBy(() -> sourceDebited.sourceDebited(TransactionId.newId(), clock))
                .isInstanceOf(InvalidTransitionException.class);
    }

    @Test
    void completedMovesSourceDebitedToCompleted() {
        TransactionId creditTxId = TransactionId.newId();
        Transfer transfer = started().sourceDebited(TransactionId.newId(), clock).completed(creditTxId, clock);

        assertThat(transfer.status()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(transfer.creditTransactionId()).isEqualTo(creditTxId);
    }

    @Test
    void completedRejectsATransferThatIsNotSourceDebited() {
        Transfer notYetDebited = started();

        assertThatThrownBy(() -> notYetDebited.completed(TransactionId.newId(), clock))
                .isInstanceOf(InvalidTransitionException.class);
    }

    // --- failed() -------------------------------------------------------------------------

    @Test
    void failedMovesStartedToFailedWithTheReason() {
        Transfer failed = started().failed(new FailureReason("ACCOUNT_INACTIVE", "origen inactivo"), clock);

        assertThat(failed.status()).isEqualTo(TransferStatus.FAILED);
        assertThat(failed.failureReason().code()).isEqualTo("ACCOUNT_INACTIVE");
    }

    @Test
    void failedRejectsATransferThatIsNotStarted() {
        Transfer sourceDebited = started().sourceDebited(TransactionId.newId(), clock);

        assertThatThrownBy(() -> sourceDebited.failed(new FailureReason("X", "x"), clock))
                .isInstanceOf(InvalidTransitionException.class);
    }

    // --- startCompensation() / compensationAttemptFailed() / compensated() / compensationFailed() ---

    @Test
    void startCompensationMovesSourceDebitedToCompensating() {
        Transfer compensating = started().sourceDebited(TransactionId.newId(), clock)
                .startCompensation(new FailureReason("ACCOUNT_INACTIVE", "destino inactivo"), clock);

        assertThat(compensating.status()).isEqualTo(TransferStatus.COMPENSATING);
    }

    @Test
    void startCompensationRejectsATransferThatIsNotSourceDebited() {
        Transfer notYetDebited = started();

        assertThatThrownBy(() -> notYetDebited.startCompensation(new FailureReason("X", "x"), clock))
                .isInstanceOf(InvalidTransitionException.class);
    }

    @Test
    void compensationAttemptFailedIncrementsTheCounterAndStaysCompensating() {
        Transfer compensating = started().sourceDebited(TransactionId.newId(), clock)
                .startCompensation(new FailureReason("ACCOUNT_INACTIVE", "destino inactivo"), clock);

        Transfer afterOneAttempt = compensating.compensationAttemptFailed(clock);
        Transfer afterTwoAttempts = afterOneAttempt.compensationAttemptFailed(clock);

        assertThat(afterTwoAttempts.status()).isEqualTo(TransferStatus.COMPENSATING);
        assertThat(afterTwoAttempts.compensationAttempts()).isEqualTo(2);
    }

    @Test
    void compensatedMovesCompensatingToCompensated() {
        Transfer compensated = started().sourceDebited(TransactionId.newId(), clock)
                .startCompensation(new FailureReason("ACCOUNT_INACTIVE", "destino inactivo"), clock)
                .compensated(clock);

        assertThat(compensated.status()).isEqualTo(TransferStatus.COMPENSATED);
    }

    @Test
    void compensationFailedMovesCompensatingToCompensationFailedAfterExhaustingRetries() {
        Transfer compensating = started().sourceDebited(TransactionId.newId(), clock)
                .startCompensation(new FailureReason("ACCOUNT_INACTIVE", "destino inactivo"), clock)
                .compensationAttemptFailed(clock)
                .compensationAttemptFailed(clock);

        Transfer compensationFailed = compensating.compensationFailed(clock);

        assertThat(compensationFailed.status()).isEqualTo(TransferStatus.COMPENSATION_FAILED);
        assertThat(compensationFailed.compensationAttempts()).isEqualTo(2);
    }

    @Test
    void compensationFailedRejectsATransferThatIsNotCompensating() {
        Transfer started = started();

        assertThatThrownBy(() -> started.compensationFailed(clock)).isInstanceOf(InvalidTransitionException.class);
    }

    // --- matches() (idempotencia, regla 2) ---------------------------------------------------

    @Test
    void matchesIsTrueWhenSourceTargetAndAmountAreTheSame() {
        Transfer transfer = started();

        assertThat(transfer.matches("acc-A", "acc-B", amount)).isTrue();
    }

    @Test
    void matchesIsFalseWhenTheAmountDiffers() {
        Transfer transfer = started();

        assertThat(transfer.matches("acc-A", "acc-B", Money.of(new BigDecimal("1.00")))).isFalse();
    }

    @Test
    void matchesIsFalseWhenTheSourceOrTargetDiffer() {
        Transfer transfer = started();

        assertThat(transfer.matches("acc-X", "acc-B", amount)).isFalse();
        assertThat(transfer.matches("acc-A", "acc-Y", amount)).isFalse();
    }
}
