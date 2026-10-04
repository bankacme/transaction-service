package com.bank.transaction.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.transaction.domain.model.Money;
import com.bank.transaction.domain.model.Transaction;
import com.bank.transaction.domain.model.TransactionId;
import com.bank.transaction.domain.model.TransactionType;
import com.bank.transaction.domain.model.Transfer;
import com.bank.transaction.infrastructure.fixture.TransactionFixtures;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Proves data-model.md 2.4's two two-collection writes commit or roll back together, the same
 * way account-service's own {@code MongoUnitOfWorkAdapterTest} proved it for {@code accounts}+
 * {@code account_operations} (and, before that, bank-spike's {@code
 * MovementTransferServiceTest}, spike-report.md check #5). Goes straight through {@link
 * MongoUnitOfWorkAdapter}'s own methods rather than composing {@link
 * TransactionPersistenceAdapter}/{@link TransferPersistenceAdapter} calls — exactly the
 * RxJava3-through-the-middle composition this adapter exists to avoid (see its own javadoc).
 *
 * <p>Needs Mongo running as a single-node replica set ({@code --replSet rs0}); a plain
 * standalone {@code mongod} rejects the transaction outright. Not runnable in this sandbox (no
 * Mongo, no network to resolve Spring Boot test dependencies) — see R4's delivery notes.
 */
@SpringBootTest
class MongoUnitOfWorkAdapterTest {

    @Autowired
    private MongoUnitOfWorkAdapter unitOfWork;

    @Autowired
    private TransferMongoRepository transferRepository;

    @Autowired
    private TransactionMongoRepository transactionRepository;

    @BeforeEach
    @AfterEach
    void cleanCollections() {
        transferRepository.deleteAll().blockingAwait();
        transactionRepository.deleteAll().blockingAwait();
    }

    @Test
    void saveTransferAndTransactionCommitsBothWritesTogetherOnSuccess() {
        String operationId = "op-tx-ok-" + UUID.randomUUID();
        Transfer transfer = TransactionFixtures.startedTransfer(operationId, "acc-A", "acc-B", "100.00")
                .sourceDebited(TransactionId.newId(), TransactionFixtures.CLOCK);
        Transaction creditLeg = Transaction.pending(transfer.operationId().forTransferIn(),
                TransactionFixtures.accountProduct("acc-B"), "cust-target", TransactionType.TRANSFER_IN,
                transfer.amount(), transfer.id(), null, null, null, TransactionFixtures.CLOCK)
                .complete(transfer.amount(), TransactionFixtures.CLOCK);
        Transfer completed = transfer.completed(creditLeg.id(), TransactionFixtures.CLOCK);

        Transfer result = unitOfWork.saveTransferAndTransaction(completed, creditLeg).blockingGet();

        assertThat(result.id()).isEqualTo(transfer.id());
        assertThat(transferRepository.existsById(transfer.id().value()).blockingGet()).isTrue();
        assertThat(transactionRepository.existsById(creditLeg.id().value()).blockingGet()).isTrue();
    }

    @Test
    void saveTransactionAndFeeCommitsBothWritesTogetherOnSuccess() {
        String operationId = "op-fee-ok-" + UUID.randomUUID();
        Transaction movement = TransactionFixtures.completedWithdrawal(operationId, "acc-A", "cust-A", "100.00",
                "900.00");
        Transaction fee = Transaction.record(movement.operationId().forFee(), movement.product(), "cust-A",
                TransactionType.FEE, Money.of("2.00"), movement.resultingBalance(), "comision",
                TransactionFixtures.CLOCK);

        Transaction result = unitOfWork.saveTransactionAndFee(movement, fee).blockingGet();

        assertThat(result.id()).isEqualTo(movement.id());
        assertThat(transactionRepository.existsById(movement.id().value()).blockingGet()).isTrue();
        assertThat(transactionRepository.existsById(fee.id().value()).blockingGet()).isTrue();
    }

    @Test
    void aFailureAfterTheFirstWriteRollsTheTransferWriteBack() {
        String operationId = "op-tx-rollback-" + UUID.randomUUID();
        Transfer transfer = TransactionFixtures.startedTransfer(operationId, "acc-A", "acc-B", "100.00");

        assertThatThrownBy(() -> unitOfWork.saveTransferForcingFailureAfterward(transfer).blockingGet())
                .isInstanceOf(IllegalStateException.class);

        // This is the assertion that actually proves atomicity, the same way account-service's
        // own test (and bank-spike's before it) proved it: the FIRST write (the transfer) must
        // be rolled back too, even though it happened before the forced failure.
        assertThat(transferRepository.existsById(transfer.id().value()).blockingGet())
                .as("the transfer write must be rolled back when a later write in the same transaction never happens")
                .isFalse();
    }
}
