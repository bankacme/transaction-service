package com.bank.transaction.infrastructure.adapter.in.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.bank.transaction.application.port.in.RecordExternalMovementUseCase;
import com.bank.transaction.application.port.in.RecoverPendingOperationsUseCase;
import com.bank.transaction.application.view.RecoveryResult;
import com.bank.transaction.domain.exception.BusinessRuleViolationException;
import com.bank.transaction.domain.model.Transaction;
import com.bank.transaction.infrastructure.fixture.TransactionFixtures;
import com.bank.transaction.infrastructure.mapper.TransactionRestMapper;
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

/** {@code /transactions/records} and {@code /transaction-recovery-runs} (both {@code
 *  x-gateway-internal: true}, not published on the Gateway — exercised directly here same as
 *  account-service's own {@code InternalMovementControllerTest} would). */
@WebFluxTest(InternalController.class)
@Import(TransactionRestMapper.class)
class InternalControllerTest {

    private static final Instant FIXTURE_CREATED_AT = TransactionFixtures.CLOCK.instant();

    @Autowired
    private WebTestClient client;

    @MockitoBean
    private RecordExternalMovementUseCase recordExternalMovementUseCase;
    @MockitoBean
    private RecoverPendingOperationsUseCase recoverPendingOperationsUseCase;
    @MockitoBean
    private Clock clock;

    @Test
    void recordingAFreshExternalMovementReturns201() {
        given(clock.instant()).willReturn(FIXTURE_CREATED_AT.minusSeconds(1));
        Transaction recorded = TransactionFixtures.recordedCardPayment("op-1", "card-1", "cust-A", "100.00",
                "900.00");
        given(recordExternalMovementUseCase.execute(any())).willReturn(Single.just(recorded));

        client.post().uri("/api/v1/transactions/records")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "operationId": "op-1",
                          "productType": "CREDIT_CARD",
                          "productId": "card-1",
                          "customerId": "cust-A",
                          "type": "CARD_PAYMENT",
                          "amount": 100.00,
                          "resultingBalance": 900.00,
                          "occurredAt": "2026-09-24T15:24:00Z"
                        }
                        """)
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.status").isEqualTo("COMPLETED");
    }

    @Test
    void replayingAnAlreadyRecordedExternalMovementReturns200() {
        given(clock.instant()).willReturn(FIXTURE_CREATED_AT.plusSeconds(60));
        Transaction recorded = TransactionFixtures.recordedCardPayment("op-1", "card-1", "cust-A", "100.00",
                "900.00");
        given(recordExternalMovementUseCase.execute(any())).willReturn(Single.just(recorded));

        client.post().uri("/api/v1/transactions/records")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "operationId": "op-1",
                          "productType": "CREDIT_CARD",
                          "productId": "card-1",
                          "customerId": "cust-A",
                          "type": "CARD_PAYMENT",
                          "amount": 100.00,
                          "resultingBalance": 900.00,
                          "occurredAt": "2026-09-24T15:24:00Z"
                        }
                        """)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void reusingAnOperationIdForADifferentExternalMovementReturns409() {
        given(clock.instant()).willReturn(FIXTURE_CREATED_AT);
        given(recordExternalMovementUseCase.execute(any())).willReturn(Single.error(
                new BusinessRuleViolationException("OPERATION_ID_REUSED", "ya se usó")));

        client.post().uri("/api/v1/transactions/records")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "operationId": "op-1",
                          "productType": "CREDIT_CARD",
                          "productId": "card-1",
                          "customerId": "cust-A",
                          "type": "CARD_PAYMENT",
                          "amount": 100.00,
                          "resultingBalance": 900.00,
                          "occurredAt": "2026-09-24T15:24:00Z"
                        }
                        """)
                .exchange()
                .expectStatus().isEqualTo(409);
    }

    @Test
    void runningRecoveryOnDemandReturnsTheResultEchoingTheRequestedThreshold() {
        given(clock.instant()).willReturn(FIXTURE_CREATED_AT);
        RecoveryResult result = new RecoveryResult(3, 2, 1, 1, 1, 0);
        given(recoverPendingOperationsUseCase.execute(5)).willReturn(Single.just(result));

        client.post().uri("/api/v1/transaction-recovery-runs?olderThanMinutes=5")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.olderThanMinutes").isEqualTo(5)
                .jsonPath("$.pendingTransactionsFound").isEqualTo(3)
                .jsonPath("$.transactionsResolved").isEqualTo(3)
                .jsonPath("$.transfersFound").isEqualTo(1)
                .jsonPath("$.transfersResolved").isEqualTo(1)
                .jsonPath("$.stillInProgress").isEqualTo(0);
    }

    @Test
    void runningRecoveryWithoutAThresholdDefaultsOlderThanMinutesToZeroInTheResponse() {
        given(clock.instant()).willReturn(FIXTURE_CREATED_AT);
        given(recoverPendingOperationsUseCase.execute(null)).willReturn(Single.just(RecoveryResult.empty()));

        client.post().uri("/api/v1/transaction-recovery-runs")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.olderThanMinutes").isEqualTo(0);
    }
}
