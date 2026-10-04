package com.bank.transaction.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.transaction.domain.exception.TransferNotFoundException;
import com.bank.transaction.domain.model.Money;
import com.bank.transaction.domain.model.OperationId;
import com.bank.transaction.domain.model.Transfer;
import com.bank.transaction.domain.model.TransferId;
import com.bank.transaction.domain.model.TransferKind;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class FindTransferUseCaseImplTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-28T10:00:00Z"), ZoneOffset.UTC);
    private final InMemoryTransferRepository repository = new InMemoryTransferRepository();
    private final FindTransferUseCaseImpl useCase = new FindTransferUseCaseImpl(repository);

    @Test
    void returnsTheTransferWhenItExists() {
        Transfer transfer = Transfer.start(new OperationId("op-1"), "acc-A", "acc-B",
                Money.of(new BigDecimal("50.00")), TransferKind.OWN, null, "cust-A", "cust-A", "cust-A", clock);
        repository.save(transfer).blockingGet();

        Transfer found = useCase.execute(transfer.id()).blockingGet();

        assertThat(found).isEqualTo(transfer);
    }

    @Test
    void rejectsAnUnknownTransferId() {
        useCase.execute(TransferId.newId()).test().assertError(TransferNotFoundException.class);
    }
}
