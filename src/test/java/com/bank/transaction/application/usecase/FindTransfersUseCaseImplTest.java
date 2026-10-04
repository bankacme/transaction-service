package com.bank.transaction.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.transaction.application.port.in.TransferFilter;
import com.bank.transaction.domain.model.Money;
import com.bank.transaction.domain.model.OperationId;
import com.bank.transaction.domain.model.Transfer;
import com.bank.transaction.domain.model.TransferKind;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** Delega en TransferRepositoryPort.findAll(filter); el filtrado en sí ya está probado contra
 *  InMemoryTransferRepository, así que aquí solo se confirma que el filtro llega intacto. */
class FindTransfersUseCaseImplTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-28T10:00:00Z"), ZoneOffset.UTC);
    private final InMemoryTransferRepository repository = new InMemoryTransferRepository();
    private final FindTransfersUseCaseImpl useCase = new FindTransfersUseCaseImpl(repository);

    @Test
    void onlyReturnsTransfersMatchingTheAccountIdFilter() {
        Transfer fromA = Transfer.start(new OperationId("op-1"), "acc-A", "acc-B",
                Money.of(new BigDecimal("50.00")), TransferKind.OWN, null, "cust-A", "cust-A", "cust-A", clock);
        Transfer fromC = Transfer.start(new OperationId("op-2"), "acc-C", "acc-D",
                Money.of(new BigDecimal("50.00")), TransferKind.OWN, null, "cust-C", "cust-C", "cust-C", clock);
        repository.save(fromA).blockingGet();
        repository.save(fromC).blockingGet();

        var results = useCase.execute(new TransferFilter("acc-A", null, null)).toList().blockingGet();

        assertThat(results).containsExactly(fromA);
    }
}
