package com.bank.transaction.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.transaction.domain.exception.BusinessRuleViolationException;
import com.bank.transaction.domain.exception.InvalidTransitionException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class TransactionTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-01T10:00:00Z"), ZoneOffset.UTC);
    private final ProductRef accountProduct = new ProductRef("acc-1", ProductType.ACCOUNT);
    private final ProductRef creditProduct = new ProductRef("cred-1", ProductType.CREDIT);
    private final Money hundred = Money.of(new BigDecimal("100.00"));

    // --- pending() ----------------------------------------------------------------------

    @Test
    void pendingCreatesAPendingTransactionWithNoResultingBalanceYet() {
        Transaction tx = Transaction.pending(new OperationId("op-1"), accountProduct, "cust-A",
                TransactionType.DEPOSIT, hundred, null, null, null, "deposito inicial", clock);

        assertThat(tx.status()).isEqualTo(TransactionStatus.PENDING);
        assertThat(tx.resultingBalance()).isNull();
        assertThat(tx.amount().amount()).isEqualByComparingTo("100.00");
    }

    @Test
    void pendingRejectsAZeroAmount() {
        assertThatThrownBy(() -> Transaction.pending(new OperationId("op-1"), accountProduct, "cust-A",
                TransactionType.DEPOSIT, Money.zero(), null, null, null, null, clock))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(e -> assertThat(((BusinessRuleViolationException) e).getErrorCode())
                        .isEqualTo("INVALID_AMOUNT"));
    }

    @Test
    void pendingRejectsATypeNotValidForTheProduct() {
        assertThatThrownBy(() -> Transaction.pending(new OperationId("op-1"), accountProduct, "cust-A",
                TransactionType.CREDIT_PAYMENT, hundred, null, null, null, null, clock))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(e -> assertThat(((BusinessRuleViolationException) e).getErrorCode())
                        .isEqualTo("INVALID_TYPE_FOR_PRODUCT"));
    }

    @Test
    void pendingLinksAFeeToItsParentTransaction() {
        TransactionId parentId = TransactionId.newId();
        Transaction fee = Transaction.pending(new OperationId("op-1-FEE"), accountProduct, "cust-A",
                TransactionType.FEE, Money.of(new BigDecimal("2.00")), null, parentId, null, null, clock);

        assertThat(fee.parentTransactionId()).isEqualTo(parentId);
    }

    // --- record() -------------------------------------------------------------------------

    @Test
    void recordCreatesAnAlreadyCompletedTransaction() {
        Transaction tx = Transaction.record(new OperationId("cr-op-1"), creditProduct, "cust-A",
                TransactionType.CREDIT_PAYMENT, hundred, Money.of(new BigDecimal("400.00")), "pago de credito",
                clock);

        assertThat(tx.status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(tx.resultingBalance().amount()).isEqualByComparingTo("400.00");
    }

    @Test
    void recordRequiresAResultingBalance() {
        assertThatThrownBy(() -> Transaction.record(new OperationId("cr-op-1"), creditProduct, "cust-A",
                TransactionType.CREDIT_PAYMENT, hundred, null, null, clock))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- complete() / fail() ----------------------------------------------------------------

    @Test
    void completeMovesAPendingTransactionToCompletedWithTheReportedBalance() {
        Transaction pending = Transaction.pending(new OperationId("op-1"), accountProduct, "cust-A",
                TransactionType.DEPOSIT, hundred, null, null, null, null, clock);

        Transaction completed = pending.complete(Money.of(new BigDecimal("1100.00")), clock);

        assertThat(completed.status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(completed.resultingBalance().amount()).isEqualByComparingTo("1100.00");
    }

    @Test
    void completeRejectsATransactionThatIsNotPending() {
        Transaction completed = Transaction.pending(new OperationId("op-1"), accountProduct, "cust-A",
                TransactionType.DEPOSIT, hundred, null, null, null, null, clock)
                .complete(Money.of(new BigDecimal("1100.00")), clock);

        assertThatThrownBy(() -> completed.complete(Money.of(new BigDecimal("1200.00")), clock))
                .isInstanceOf(InvalidTransitionException.class);
    }

    @Test
    void failMovesAPendingTransactionToFailedAndKeepsTheReason() {
        Transaction pending = Transaction.pending(new OperationId("op-1"), accountProduct, "cust-A",
                TransactionType.WITHDRAWAL, hundred, null, null, null, null, clock);

        Transaction failed = pending.fail(new FailureReason("INSUFFICIENT_FUNDS", "no alcanza"), clock);

        assertThat(failed.status()).isEqualTo(TransactionStatus.FAILED);
        assertThat(failed.failureReason().code()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(failed.resultingBalance()).isNull();
    }

    @Test
    void failRejectsATransactionThatIsNotPending() {
        Transaction completed = Transaction.record(new OperationId("cr-op-1"), creditProduct, "cust-A",
                TransactionType.CREDIT_PAYMENT, hundred, Money.of(new BigDecimal("400.00")), null, clock);

        assertThatThrownBy(() -> completed.fail(new FailureReason("X", "x"), clock))
                .isInstanceOf(InvalidTransitionException.class);
    }

    // --- markReversed() / markReversalFailed() ----------------------------------------------

    @Test
    void markReversedMovesACompletedTransactionToReversed() {
        Transaction completed = Transaction.record(new OperationId("cr-op-1"), creditProduct, "cust-A",
                TransactionType.CREDIT_PAYMENT, hundred, Money.of(new BigDecimal("400.00")), null, clock);
        TransactionReversal reversal = new TransactionReversal(new OperationId("cr-op-1-REV"),
                ReversalOutcome.REVERSED, null);

        Transaction reversed = completed.markReversed(reversal, clock);

        assertThat(reversed.status()).isEqualTo(TransactionStatus.REVERSED);
        assertThat(reversed.reversal()).isEqualTo(reversal);
    }

    @Test
    void markReversedRejectsANonSuccessfulReversal() {
        Transaction completed = Transaction.record(new OperationId("cr-op-1"), creditProduct, "cust-A",
                TransactionType.CREDIT_PAYMENT, hundred, Money.of(new BigDecimal("400.00")), null, clock);
        TransactionReversal rejected = new TransactionReversal(new OperationId("cr-op-1-REV"),
                ReversalOutcome.REVERSAL_FAILED, "INSUFFICIENT_FUNDS");

        assertThatThrownBy(() -> completed.markReversed(rejected, clock))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void markReversedRejectsATransactionThatIsNotCompleted() {
        Transaction pending = Transaction.pending(new OperationId("op-1"), accountProduct, "cust-A",
                TransactionType.DEPOSIT, hundred, null, null, null, null, clock);
        TransactionReversal reversal = new TransactionReversal(new OperationId("op-1-REV"),
                ReversalOutcome.REVERSED, null);

        assertThatThrownBy(() -> pending.markReversed(reversal, clock))
                .isInstanceOf(InvalidTransitionException.class);
    }

    @Test
    void markReversalFailedKeepsTheTransactionCompletedButAnnotatesTheAttempt() {
        Transaction completed = Transaction.record(new OperationId("cr-op-1"), creditProduct, "cust-A",
                TransactionType.CREDIT_PAYMENT, hundred, Money.of(new BigDecimal("400.00")), null, clock);
        TransactionReversal failedAttempt = new TransactionReversal(new OperationId("cr-op-1-REV"),
                ReversalOutcome.REVERSAL_FAILED, "ACCOUNT_INACTIVE");

        Transaction annotated = completed.markReversalFailed(failedAttempt, clock);

        assertThat(annotated.status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(annotated.reversal().reasonCode()).isEqualTo("ACCOUNT_INACTIVE");
    }

    // --- updateDescription() / discard() -----------------------------------------------------

    @Test
    void updateDescriptionChangesOnlyTheDescription() {
        Transaction pending = Transaction.pending(new OperationId("op-1"), accountProduct, "cust-A",
                TransactionType.DEPOSIT, hundred, null, null, null, "original", clock);

        Transaction updated = pending.updateDescription("corregida", clock);

        assertThat(updated.description()).isEqualTo("corregida");
        assertThat(updated.status()).isEqualTo(TransactionStatus.PENDING);
        assertThat(updated.amount()).isEqualTo(pending.amount());
    }

    @Test
    void updateDescriptionRejectsEditingADiscardedTransaction() {
        Transaction discarded = Transaction.pending(new OperationId("op-1"), accountProduct, "cust-A",
                TransactionType.DEPOSIT, hundred, null, null, null, null, clock)
                .fail(new FailureReason("INSUFFICIENT_FUNDS", "no alcanza"), clock)
                .discard(clock);

        assertThatThrownBy(() -> discarded.updateDescription("nueva", clock))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(e -> assertThat(((BusinessRuleViolationException) e).getErrorCode())
                        .isEqualTo("TRANSACTION_DISCARDED"));
    }

    @Test
    void discardMovesAFailedTransactionToDiscarded() {
        Transaction failed = Transaction.pending(new OperationId("op-1"), accountProduct, "cust-A",
                TransactionType.DEPOSIT, hundred, null, null, null, null, clock)
                .fail(new FailureReason("INSUFFICIENT_FUNDS", "no alcanza"), clock);

        Transaction discarded = failed.discard(clock);

        assertThat(discarded.status()).isEqualTo(TransactionStatus.DISCARDED);
    }

    @Test
    void discardRejectsATransactionThatIsNotFailed() {
        Transaction completed = Transaction.record(new OperationId("cr-op-1"), creditProduct, "cust-A",
                TransactionType.CREDIT_PAYMENT, hundred, Money.of(new BigDecimal("400.00")), null, clock);

        assertThatThrownBy(() -> completed.discard(clock))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(e -> assertThat(((BusinessRuleViolationException) e).getErrorCode())
                        .isEqualTo("NOT_DISCARDABLE"));
    }

    // --- matches() (idempotencia, regla 2) ---------------------------------------------------

    @Test
    void matchesIsTrueWhenProductTypeAndAmountAreTheSame() {
        Transaction tx = Transaction.pending(new OperationId("op-1"), accountProduct, "cust-A",
                TransactionType.DEPOSIT, hundred, null, null, null, "deposito inicial", clock);

        assertThat(tx.matches(accountProduct, TransactionType.DEPOSIT, hundred)).isTrue();
    }

    @Test
    void matchesIsFalseWhenTheAmountDiffers() {
        Transaction tx = Transaction.pending(new OperationId("op-1"), accountProduct, "cust-A",
                TransactionType.DEPOSIT, hundred, null, null, null, null, clock);

        assertThat(tx.matches(accountProduct, TransactionType.DEPOSIT, Money.of(new BigDecimal("50.00"))))
                .isFalse();
    }

    @Test
    void matchesIsFalseWhenTheProductOrTypeDiffer() {
        Transaction tx = Transaction.pending(new OperationId("op-1"), accountProduct, "cust-A",
                TransactionType.DEPOSIT, hundred, null, null, null, null, clock);

        assertThat(tx.matches(new ProductRef("acc-2", ProductType.ACCOUNT), TransactionType.DEPOSIT, hundred))
                .isFalse();
        assertThat(tx.matches(accountProduct, TransactionType.WITHDRAWAL, hundred)).isFalse();
    }
}
