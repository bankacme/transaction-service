package com.bank.transaction.infrastructure.fixture;

import com.bank.transaction.domain.model.FailureReason;
import com.bank.transaction.domain.model.Money;
import com.bank.transaction.domain.model.OperationId;
import com.bank.transaction.domain.model.ProductRef;
import com.bank.transaction.domain.model.ProductType;
import com.bank.transaction.domain.model.ReversalOutcome;
import com.bank.transaction.domain.model.Transaction;
import com.bank.transaction.domain.model.TransactionReversal;
import com.bank.transaction.domain.model.TransactionType;
import com.bank.transaction.domain.model.Transfer;
import com.bank.transaction.domain.model.TransferKind;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Shared object-mothers for the R4 persistence tests (mapper round-trips, repository/adapter
 * integration tests) — mirrors account-service's own {@code AccountFixtures}. Several methods
 * deliberately drive a {@link Transaction}/{@link Transfer} through more than one transition
 * so the nested objects a mapper must round-trip ({@code FailureReason}, {@code
 * TransactionReversal}, the derived transfer-leg operationIds) are actually populated, not
 * left null the way a freshly-{@code pending} one would leave them.
 */
public final class TransactionFixtures {

    public static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-27T10:00:00Z"), ZoneOffset.UTC);

    private TransactionFixtures() {
    }

    public static ProductRef accountProduct(String productId) {
        return new ProductRef(productId, ProductType.ACCOUNT);
    }

    public static Transaction pendingDeposit(String operationId, String productId, String customerId,
                                              String amount) {
        return Transaction.pending(new OperationId(operationId), accountProduct(productId), customerId,
                TransactionType.DEPOSIT, Money.of(amount), null, null, null, "deposito de prueba", CLOCK);
    }

    public static Transaction completedWithdrawal(String operationId, String productId, String customerId,
                                                    String amount, String resultingBalance) {
        Transaction pending = Transaction.pending(new OperationId(operationId), accountProduct(productId),
                customerId, TransactionType.WITHDRAWAL, Money.of(amount), null, null, null, null, CLOCK);
        return pending.complete(Money.of(resultingBalance), CLOCK);
    }

    public static Transaction failedWithdrawal(String operationId, String productId, String customerId,
                                                 String amount, String errorCode, String errorMessage) {
        Transaction pending = Transaction.pending(new OperationId(operationId), accountProduct(productId),
                customerId, TransactionType.WITHDRAWAL, Money.of(amount), null, null, null, null, CLOCK);
        return pending.fail(new FailureReason(errorCode, errorMessage), CLOCK);
    }

    /** A completed debit leg of a transfer, later annotated with a successful reversal — the
     *  only path that actually populates {@code reversal} (data-model.md 3.1), needed to
     *  exercise the mapper's nested {@code ReversalData} mapping. */
    public static Transaction reversedTransferOutLeg(String operationId, String productId, String customerId,
                                                        com.bank.transaction.domain.model.TransferId transferId,
                                                        String amount, String resultingBalance) {
        Transaction completed = Transaction.pending(new OperationId(operationId).forTransferOut(),
                accountProduct(productId), customerId, TransactionType.TRANSFER_OUT, Money.of(amount), transferId,
                null, null, null, CLOCK).complete(Money.of(resultingBalance), CLOCK);
        TransactionReversal reversal = new TransactionReversal(completed.operationId().forReversal(),
                ReversalOutcome.REVERSED, null);
        return completed.markReversed(reversal, CLOCK);
    }

    /** A completed debit leg whose reversal was attempted and rejected — populates {@code
     *  reversal} with a {@code REVERSAL_FAILED} outcome instead, the other shape {@code
     *  ReversalData} must round-trip. */
    public static Transaction reversalFailedTransferOutLeg(String operationId, String productId, String customerId,
                                                              com.bank.transaction.domain.model.TransferId transferId,
                                                              String amount, String resultingBalance,
                                                              String reasonCode) {
        Transaction completed = Transaction.pending(new OperationId(operationId).forTransferOut(),
                accountProduct(productId), customerId, TransactionType.TRANSFER_OUT, Money.of(amount), transferId,
                null, null, null, CLOCK).complete(Money.of(resultingBalance), CLOCK);
        TransactionReversal reversal = new TransactionReversal(completed.operationId().forReversal(),
                ReversalOutcome.REVERSAL_FAILED, reasonCode);
        return completed.markReversalFailed(reversal, CLOCK);
    }

    public static Transaction recordedCardPayment(String operationId, String productId, String customerId,
                                                     String amount, String resultingBalance) {
        ProductRef product = new ProductRef(productId, ProductType.CREDIT_CARD);
        return Transaction.record(new OperationId(operationId), product, customerId, TransactionType.CARD_PAYMENT,
                Money.of(amount), Money.of(resultingBalance), "pago de tarjeta", CLOCK);
    }

    public static Transfer startedTransfer(String operationId, String sourceAccountId, String targetAccountId,
                                             String amount) {
        return Transfer.start(new OperationId(operationId), sourceAccountId, targetAccountId, Money.of(amount),
                TransferKind.OWN, "transferencia de prueba", "cust-source", "cust-target", "cust-source", CLOCK);
    }

    /** A transfer driven all the way to COMPENSATED, with both {@code failureReason},
     *  {@code debitTransactionId} and a non-zero {@code compensationAttempts}/{@code version}
     *  populated — the most nested-field-dense state a {@code Transfer} can reach, used to
     *  exercise the mapper as thoroughly as {@code AccountDocumentMapperTest} does. */
    public static Transfer compensatedTransfer(String operationId, String sourceAccountId, String targetAccountId,
                                                 String amount) {
        com.bank.transaction.domain.model.TransactionId debitTransactionId =
                com.bank.transaction.domain.model.TransactionId.newId();
        Transfer started = Transfer.start(new OperationId(operationId), sourceAccountId, targetAccountId,
                Money.of(amount), TransferKind.THIRD_PARTY, "transferencia a terceros", "cust-source", "cust-target",
                "cust-source", CLOCK);
        Transfer sourceDebited = started.sourceDebited(debitTransactionId, CLOCK);
        Transfer compensating = sourceDebited.startCompensation(
                new FailureReason("ACCOUNT_INACTIVE", "cuenta destino inactiva"), CLOCK);
        Transfer oneFailedAttempt = compensating.compensationAttemptFailed(CLOCK);
        return oneFailedAttempt.compensated(CLOCK);
    }
}
