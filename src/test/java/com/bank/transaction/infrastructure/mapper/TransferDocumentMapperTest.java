package com.bank.transaction.infrastructure.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.transaction.domain.model.Transfer;
import com.bank.transaction.infrastructure.adapter.out.persistence.TransferDocument;
import com.bank.transaction.infrastructure.fixture.TransactionFixtures;
import org.junit.jupiter.api.Test;

/** Pure JUnit, no Spring context — same reasoning as {@link TransactionDocumentMapperTest}. */
class TransferDocumentMapperTest {

    private final TransferDocumentMapper mapper = new TransferDocumentMapper();

    @Test
    void roundTripsAFreshlyStartedTransferWithNoOptionalFields() {
        Transfer started = TransactionFixtures.startedTransfer("op-1", "acc-A", "acc-B", "100.00");

        TransferDocument document = mapper.toDocument(started);

        assertThat(document.getId()).isEqualTo(started.id().value());
        assertThat(document.getOperationId()).isEqualTo("op-1");
        assertThat(document.getKind()).isEqualTo("OWN");
        assertThat(document.getStatus()).isEqualTo("STARTED");
        assertThat(document.getAmount()).isEqualByComparingTo("100.00");
        assertThat(document.getFailureReason()).isNull();
        assertThat(document.getDebitTransactionId()).isNull();
        assertThat(document.getCreditTransactionId()).isNull();
        assertThat(document.getCompensationAttempts()).isZero();
        assertThat(document.getVersion()).isZero();
        assertThat(mapper.toDomain(document)).isEqualTo(started);
    }

    @Test
    void roundTripsAFullyCompensatedTransfersFailureReasonAndAttemptCount() {
        Transfer compensated = TransactionFixtures.compensatedTransfer("op-2", "acc-A", "acc-B", "300.00");

        TransferDocument document = mapper.toDocument(compensated);

        assertThat(document.getKind()).isEqualTo("THIRD_PARTY");
        assertThat(document.getStatus()).isEqualTo("COMPENSATED");
        assertThat(document.getFailureReason().getCode()).isEqualTo("ACCOUNT_INACTIVE");
        assertThat(document.getDebitTransactionId()).isNotNull();
        assertThat(document.getCreditTransactionId()).isNull(); // never reached COMPLETED
        assertThat(document.getCompensationAttempts()).isEqualTo(1);
        assertThat(mapper.toDomain(document)).isEqualTo(compensated);
    }
}
