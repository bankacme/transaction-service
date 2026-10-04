package com.bank.transaction.infrastructure.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.transaction.domain.model.Transaction;
import com.bank.transaction.domain.model.TransferId;
import com.bank.transaction.infrastructure.adapter.out.persistence.TransactionDocument;
import com.bank.transaction.infrastructure.fixture.TransactionFixtures;
import org.junit.jupiter.api.Test;

/**
 * No {@code @SpringBootTest} needed — a hand-written mapper is plain object-to-object code,
 * same approach {@code account-service}'s own {@code AccountDocumentMapperTest} takes. Every
 * branch that can be null ({@code resultingBalance}, {@code failureReason}, {@code
 * transferId}/{@code parentTransactionId}, {@code reversal} in both its {@code REVERSED}/
 * {@code REVERSAL_FAILED} shapes) gets its own round trip.
 */
class TransactionDocumentMapperTest {

    private final TransactionDocumentMapper mapper = new TransactionDocumentMapper();

    @Test
    void roundTripsAPendingDepositWithNoOptionalFields() {
        Transaction pending = TransactionFixtures.pendingDeposit("op-1", "acc-1", "cust-A", "100.00");

        TransactionDocument document = mapper.toDocument(pending);

        assertThat(document.getId()).isEqualTo(pending.id().value());
        assertThat(document.getOperationId()).isEqualTo("op-1");
        assertThat(document.getProductId()).isEqualTo("acc-1");
        assertThat(document.getProductType()).isEqualTo("ACCOUNT");
        assertThat(document.getType()).isEqualTo("DEPOSIT");
        assertThat(document.getAmount()).isEqualByComparingTo("100.00");
        assertThat(document.getResultingBalance()).isNull();
        assertThat(document.getFailureReason()).isNull();
        assertThat(document.getTransferId()).isNull();
        assertThat(document.getReversal()).isNull();
        assertThat(mapper.toDomain(document)).isEqualTo(pending);
    }

    @Test
    void roundTripsACompletedWithdrawalsResultingBalance() {
        Transaction completed = TransactionFixtures.completedWithdrawal("op-2", "acc-1", "cust-A", "50.00",
                "450.00");

        TransactionDocument document = mapper.toDocument(completed);

        assertThat(document.getResultingBalance()).isEqualByComparingTo("450.00");
        assertThat(document.getStatus()).isEqualTo("COMPLETED");
        assertThat(mapper.toDomain(document)).isEqualTo(completed);
    }

    @Test
    void roundTripsAFailedTransactionsFailureReason() {
        Transaction failed = TransactionFixtures.failedWithdrawal("op-3", "acc-1", "cust-A", "50.00",
                "INSUFFICIENT_FUNDS", "no alcanza el saldo");

        TransactionDocument document = mapper.toDocument(failed);

        assertThat(document.getFailureReason().getCode()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(document.getFailureReason().getMessage()).isEqualTo("no alcanza el saldo");
        assertThat(mapper.toDomain(document)).isEqualTo(failed);
    }

    @Test
    void roundTripsATransferLegsTransferIdAndASuccessfulReversal() {
        TransferId transferId = TransferId.newId();
        Transaction reversed = TransactionFixtures.reversedTransferOutLeg("op-4", "acc-1", "cust-A", transferId,
                "200.00", "800.00");

        TransactionDocument document = mapper.toDocument(reversed);

        assertThat(document.getTransferId()).isEqualTo(transferId.value());
        assertThat(document.getStatus()).isEqualTo("REVERSED");
        assertThat(document.getReversal().getOutcome()).isEqualTo("REVERSED");
        assertThat(document.getReversal().getReasonCode()).isNull();
        assertThat(mapper.toDomain(document)).isEqualTo(reversed);
    }

    @Test
    void roundTripsARejectedReversalsReasonCodeWithoutChangingStatus() {
        TransferId transferId = TransferId.newId();
        Transaction annotated = TransactionFixtures.reversalFailedTransferOutLeg("op-5", "acc-1", "cust-A",
                transferId, "200.00", "800.00", "ACCOUNT_INACTIVE");

        TransactionDocument document = mapper.toDocument(annotated);

        // status stays COMPLETED (Transaction.markReversalFailed does not change it) — only the
        // reversal annotation itself carries the failed attempt.
        assertThat(document.getStatus()).isEqualTo("COMPLETED");
        assertThat(document.getReversal().getOutcome()).isEqualTo("REVERSAL_FAILED");
        assertThat(document.getReversal().getReasonCode()).isEqualTo("ACCOUNT_INACTIVE");
        assertThat(mapper.toDomain(document)).isEqualTo(annotated);
    }

    @Test
    void roundTripsARecordedCardPaymentsCreditCardProductType() {
        Transaction recorded = TransactionFixtures.recordedCardPayment("op-6", "card-1", "cust-A", "75.00",
                "925.00");

        TransactionDocument document = mapper.toDocument(recorded);

        assertThat(document.getProductType()).isEqualTo("CREDIT_CARD");
        assertThat(document.getType()).isEqualTo("CARD_PAYMENT");
        assertThat(document.getStatus()).isEqualTo("COMPLETED");
        assertThat(mapper.toDomain(document)).isEqualTo(recorded);
    }
}
