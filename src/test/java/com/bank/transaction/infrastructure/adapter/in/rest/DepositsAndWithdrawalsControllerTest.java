package com.bank.transaction.infrastructure.adapter.in.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.bank.transaction.application.port.in.RegisterDepositUseCase;
import com.bank.transaction.application.port.in.RegisterWithdrawalUseCase;
import com.bank.transaction.domain.exception.AccountNotFoundException;
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

/**
 * {@code POST /deposits} and {@code /withdrawals}. {@code FIXTURE_CREATED_AT} is {@link
 * TransactionFixtures#CLOCK}'s own fixed instant (every fixture transaction is "created" then);
 * each test controls the controller's own injected {@code Clock} to land either before it (this
 * request "created" the row -> 201) or after it (the row already existed -> 200) — see {@code
 * TransactionRestMapper#wasCreatedByThisRequest}.
 */
@WebFluxTest(DepositsAndWithdrawalsController.class)
@Import(TransactionRestMapper.class)
class DepositsAndWithdrawalsControllerTest {

    private static final Instant FIXTURE_CREATED_AT = TransactionFixtures.CLOCK.instant();

    @Autowired
    private WebTestClient client;

    @MockitoBean
    private RegisterDepositUseCase registerDepositUseCase;
    @MockitoBean
    private RegisterWithdrawalUseCase registerWithdrawalUseCase;
    @MockitoBean
    private Clock clock;

    @Test
    void aFreshlyCompletedDepositReturns201() {
        given(clock.instant()).willReturn(FIXTURE_CREATED_AT.minusSeconds(1));
        Transaction completed = TransactionFixtures.completedWithdrawal("op-00001", "acc-1", "cust-A", "100.00",
                "900.00");
        given(registerDepositUseCase.execute(any())).willReturn(Single.just(completed));

        client.post().uri("/api/v1/deposits")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "operationId": "op-00001",
                          "accountId": "acc-1",
                          "amount": 100.00
                        }
                        """)
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.transaction.id").isEqualTo(completed.id().value())
                .jsonPath("$.transaction.status").isEqualTo("COMPLETED");
    }

    @Test
    void aReplayedAlreadyCompletedDepositReturns200() {
        given(clock.instant()).willReturn(FIXTURE_CREATED_AT.plusSeconds(60));
        Transaction completed = TransactionFixtures.completedWithdrawal("op-00001", "acc-1", "cust-A", "100.00",
                "900.00");
        given(registerDepositUseCase.execute(any())).willReturn(Single.just(completed));

        client.post().uri("/api/v1/deposits")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "operationId": "op-00001",
                          "accountId": "acc-1",
                          "amount": 100.00
                        }
                        """)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void aStillPendingDepositReturns202() {
        given(clock.instant()).willReturn(FIXTURE_CREATED_AT);
        Transaction pending = TransactionFixtures.pendingDeposit("op-00001", "acc-1", "cust-A", "100.00");
        given(registerDepositUseCase.execute(any())).willReturn(Single.just(pending));

        client.post().uri("/api/v1/deposits")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "operationId": "op-00001",
                          "accountId": "acc-1",
                          "amount": 100.00
                        }
                        """)
                .exchange()
                .expectStatus().isEqualTo(202)
                .expectBody()
                .jsonPath("$.transaction.status").isEqualTo("PENDING");
    }

    @Test
    void aRejectedDepositReturns422WithTheTransactionIdAndTheAccountsReasonCode() {
        given(clock.instant()).willReturn(FIXTURE_CREATED_AT);
        Transaction failed = TransactionFixtures.failedWithdrawal("op-00001", "acc-1", "cust-A", "100.00",
                "ACCOUNT_INACTIVE", "cuenta inactiva");
        given(registerDepositUseCase.execute(any())).willReturn(Single.just(failed));

        client.post().uri("/api/v1/deposits")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "operationId": "op-00001",
                          "accountId": "acc-1",
                          "amount": 100.00
                        }
                        """)
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("ACCOUNT_INACTIVE")
                .jsonPath("$.transactionId").isEqualTo(failed.id().value());
    }

    @Test
    void aDepositForAMissingAccountReturns404() {
        given(clock.instant()).willReturn(FIXTURE_CREATED_AT);
        given(registerDepositUseCase.execute(any()))
                .willReturn(Single.error(new AccountNotFoundException("missing-account")));

        client.post().uri("/api/v1/deposits")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "operationId": "op-00001",
                          "accountId": "missing-account",
                          "amount": 100.00
                        }
                        """)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo("ACCOUNT_NOT_FOUND");
    }

    @Test
    void reusingAnOperationIdWithDifferentDataReturns409() {
        given(clock.instant()).willReturn(FIXTURE_CREATED_AT);
        given(registerDepositUseCase.execute(any())).willReturn(Single.error(
                new BusinessRuleViolationException("OPERATION_ID_REUSED", "ya se usó con otros datos")));

        client.post().uri("/api/v1/deposits")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "operationId": "op-00001",
                          "accountId": "acc-1",
                          "amount": 100.00
                        }
                        """)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.code").isEqualTo("OPERATION_ID_REUSED");
    }

    @Test
    void aDepositWithoutARequiredFieldReturns400ValidationError() {
        client.post().uri("/api/v1/deposits")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "operationId": "op-00001",
                          "amount": 100.00
                        }
                        """)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("VALIDATION_ERROR");
    }

    /** Only confirms a withdrawal routes through the same shaping as a deposit — the full
     *  status-mapping battery already lives above, same economy-of-tests reasoning as
     *  account-service's own {@code RegisterWithdrawalUseCaseImplTest}. */
    @Test
    void aFreshlyCompletedWithdrawalReturns201() {
        given(clock.instant()).willReturn(FIXTURE_CREATED_AT.minusSeconds(1));
        Transaction completed = TransactionFixtures.completedWithdrawal("op-00002", "acc-1", "cust-A", "50.00",
                "850.00");
        given(registerWithdrawalUseCase.execute(any())).willReturn(Single.just(completed));

        client.post().uri("/api/v1/withdrawals")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "operationId": "op-00002",
                          "accountId": "acc-1",
                          "amount": 50.00
                        }
                        """)
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.transaction.status").isEqualTo("COMPLETED");
    }
}
