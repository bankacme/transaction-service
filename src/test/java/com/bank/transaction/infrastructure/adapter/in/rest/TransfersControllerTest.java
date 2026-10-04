package com.bank.transaction.infrastructure.adapter.in.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import com.bank.transaction.application.port.in.FindTransferUseCase;
import com.bank.transaction.application.port.in.FindTransfersUseCase;
import com.bank.transaction.application.port.in.StartTransferUseCase;
import com.bank.transaction.domain.exception.AccountNotFoundException;
import com.bank.transaction.domain.exception.TransferNotFoundException;
import com.bank.transaction.domain.model.FailureReason;
import com.bank.transaction.domain.model.Money;
import com.bank.transaction.domain.model.OperationId;
import com.bank.transaction.domain.model.TransactionId;
import com.bank.transaction.domain.model.Transfer;
import com.bank.transaction.domain.model.TransferId;
import com.bank.transaction.domain.model.TransferKind;
import com.bank.transaction.infrastructure.fixture.TransactionFixtures;
import com.bank.transaction.infrastructure.mapper.TransactionRestMapper;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

/** {@code /transfers} and {@code /transfers/{id}}. Same {@code FIXTURE_CREATED_AT} trick as
 *  {@code DepositsAndWithdrawalsControllerTest} for the 200-vs-201 split. */
@WebFluxTest(TransfersController.class)
@Import(TransactionRestMapper.class)
class TransfersControllerTest {

    private static final Instant FIXTURE_CREATED_AT = TransactionFixtures.CLOCK.instant();

    @Autowired
    private WebTestClient client;

    @MockitoBean
    private StartTransferUseCase startTransferUseCase;
    @MockitoBean
    private FindTransfersUseCase findTransfersUseCase;
    @MockitoBean
    private FindTransferUseCase findTransferUseCase;
    @MockitoBean
    private Clock clock;

    private Transfer completedTransfer() {
        Transfer started = Transfer.start(new OperationId("op-00001"), "acc-A", "acc-B", Money.of("100.00"),
                TransferKind.OWN, "pago", "cust-A", "cust-B", "cust-A", TransactionFixtures.CLOCK);
        Transfer sourceDebited = started.sourceDebited(TransactionId.newId(), TransactionFixtures.CLOCK);
        return sourceDebited.completed(TransactionId.newId(), TransactionFixtures.CLOCK);
    }

    @Test
    void aFreshlyCompletedTransferReturns201() {
        given(clock.instant()).willReturn(FIXTURE_CREATED_AT.minusSeconds(1));
        Transfer completed = completedTransfer();
        given(startTransferUseCase.execute(any())).willReturn(Single.just(completed));

        client.post().uri("/api/v1/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "operationId": "op-00001",
                          "sourceAccountId": "acc-A",
                          "targetAccountId": "acc-B",
                          "amount": 100.00
                        }
                        """)
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.status").isEqualTo("COMPLETED");
    }

    @Test
    void aReplayedAlreadyCompletedTransferReturns200() {
        given(clock.instant()).willReturn(FIXTURE_CREATED_AT.plusSeconds(60));
        given(startTransferUseCase.execute(any())).willReturn(Single.just(completedTransfer()));

        client.post().uri("/api/v1/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "operationId": "op-00001",
                          "sourceAccountId": "acc-A",
                          "targetAccountId": "acc-B",
                          "amount": 100.00
                        }
                        """)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void aTransferStillInProgressReturns202() {
        given(clock.instant()).willReturn(FIXTURE_CREATED_AT);
        Transfer started = TransactionFixtures.startedTransfer("op-00001", "acc-A", "acc-B", "100.00");
        given(startTransferUseCase.execute(any())).willReturn(Single.just(started));

        client.post().uri("/api/v1/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "operationId": "op-00001",
                          "sourceAccountId": "acc-A",
                          "targetAccountId": "acc-B",
                          "amount": 100.00
                        }
                        """)
                .exchange()
                .expectStatus().isEqualTo(202)
                .expectBody()
                .jsonPath("$.status").isEqualTo("STARTED");
    }

    @Test
    void aCompensatedTransferReturns422WithTheTransferIdAndStatus() {
        given(clock.instant()).willReturn(FIXTURE_CREATED_AT);
        Transfer compensated = TransactionFixtures.compensatedTransfer("op-00001", "acc-A", "acc-B", "100.00");
        given(startTransferUseCase.execute(any())).willReturn(Single.just(compensated));

        client.post().uri("/api/v1/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "operationId": "op-00001",
                          "sourceAccountId": "acc-A",
                          "targetAccountId": "acc-B",
                          "amount": 100.00
                        }
                        """)
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("ACCOUNT_INACTIVE")
                .jsonPath("$.transferId").isEqualTo(compensated.id().value())
                .jsonPath("$.transferStatus").isEqualTo("COMPENSATED");
    }

    @Test
    void aFailedTransferReturns422() {
        given(clock.instant()).willReturn(FIXTURE_CREATED_AT);
        Transfer started = Transfer.start(new OperationId("op-00001"), "acc-A", "acc-B", Money.of("100.00"),
                TransferKind.OWN, "pago", "cust-A", "cust-B", "cust-A", TransactionFixtures.CLOCK);
        Transfer failed = started.failed(new FailureReason("INSUFFICIENT_FUNDS", "saldo insuficiente"),
                TransactionFixtures.CLOCK);
        given(startTransferUseCase.execute(any())).willReturn(Single.just(failed));

        client.post().uri("/api/v1/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "operationId": "op-00001",
                          "sourceAccountId": "acc-A",
                          "targetAccountId": "acc-B",
                          "amount": 100.00
                        }
                        """)
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("INSUFFICIENT_FUNDS")
                .jsonPath("$.transferStatus").isEqualTo("FAILED");
    }

    @Test
    void aTransferFromAMissingSourceAccountReturns404() {
        given(clock.instant()).willReturn(FIXTURE_CREATED_AT);
        given(startTransferUseCase.execute(any()))
                .willReturn(Single.error(new AccountNotFoundException("missing-account")));

        client.post().uri("/api/v1/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "operationId": "op-00001",
                          "sourceAccountId": "missing-account",
                          "targetAccountId": "acc-B",
                          "amount": 100.00
                        }
                        """)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void listTransfersReturnsWhateverTheUseCaseStreams() {
        given(findTransfersUseCase.execute(any()))
                .willReturn(Flowable.just(TransactionFixtures.startedTransfer("op-00001", "acc-A", "acc-B", "100.00")));

        client.get().uri("/api/v1/transfers?accountId=acc-A")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(Object.class).hasSize(1);
    }

    @Test
    void getTransferReturnsTheTransfer() {
        Transfer transfer = TransactionFixtures.startedTransfer("op-00001", "acc-A", "acc-B", "100.00");
        given(findTransferUseCase.execute(eq(transfer.id()))).willReturn(Single.just(transfer));

        client.get().uri("/api/v1/transfers/{id}", transfer.id().value())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.sourceAccountId").isEqualTo("acc-A");
    }

    @Test
    void getTransferThatDoesNotExistReturns404() {
        given(findTransferUseCase.execute(eq(new TransferId("missing"))))
                .willReturn(Single.error(new TransferNotFoundException("missing")));

        client.get().uri("/api/v1/transfers/missing")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo("TRANSFER_NOT_FOUND");
    }
}
