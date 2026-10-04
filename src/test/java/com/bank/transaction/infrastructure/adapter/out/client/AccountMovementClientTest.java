package com.bank.transaction.infrastructure.adapter.out.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.bank.transaction.domain.exception.DownstreamServiceUnavailableException;
import com.bank.transaction.domain.model.Money;
import com.bank.transaction.domain.model.MovementOutcome;
import com.bank.transaction.domain.model.OperationId;
import com.bank.transaction.domain.model.TransactionType;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.reactivex.rxjava3.observers.TestObserver;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Same no-Spring-context, WireMock-as-account-service setup as {@link AccountLookupClientTest}
 * and account-service's own {@code CustomerServiceClientTest}. The key cases this adds beyond
 * that shared template are the ones specific to {@link AccountMovementClient}'s central design
 * decision (see its class javadoc): a 422/404 with the contract's {@code ErrorResponse} body
 * must surface as a <em>rejected</em> {@link MovementOutcome} — {@code assertNoErrors()}, not
 * {@code assertError(...)} — while only a timeout/500/open circuit becomes {@link
 * DownstreamServiceUnavailableException}.
 */
class AccountMovementClientTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private AccountMovementClient client;

    @BeforeEach
    void setUp() {
        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(5))
                .build();
        TimeLimiterConfig tlConfig = TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofSeconds(2))
                .cancelRunningFuture(true)
                .build();

        client = new AccountMovementClient(
                WebClient.builder(),
                wireMock.baseUrl(),
                CircuitBreakerRegistry.of(cbConfig),
                TimeLimiterRegistry.of(tlConfig));
    }

    @Test
    void anAppliedMovementIsMappedFromTheResponseBody() throws InterruptedException {
        wireMock.stubFor(post(urlEqualTo("/accounts/acc-1/movements")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {
                          "operationId": "op-1",
                          "accountId": "acc-1",
                          "type": "WITHDRAWAL",
                          "amount": 150.00,
                          "fee": 2.00,
                          "newBalance": 848.00,
                          "movementNumber": 6
                        }
                        """)));

        TestObserver<MovementOutcome> observer = client.apply(new OperationId("op-1"), "acc-1",
                TransactionType.WITHDRAWAL, Money.of("150.00"), LocalDate.of(2026, 9, 24)).test();
        observer.await();

        observer.assertValue(MovementOutcome.applied(new OperationId("op-1"), Money.of("848.00"), Money.of("2.00")));
    }

    @Test
    void transferOutIsSentAsAWithdrawal() throws InterruptedException {
        wireMock.stubFor(post(urlEqualTo("/accounts/acc-1/movements")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"operationId": "op-1-OUT", "accountId": "acc-1", "type": "WITHDRAWAL",
                         "amount": 50.00, "fee": 0.00, "newBalance": 450.00, "movementNumber": 1}
                        """)));

        client.apply(new OperationId("op-1-OUT"), "acc-1", TransactionType.TRANSFER_OUT, Money.of("50.00"),
                LocalDate.of(2026, 9, 24)).test().await();

        wireMock.verify(postRequestedFor(urlEqualTo("/accounts/acc-1/movements"))
                .withRequestBody(matchingJsonPath("$.type", equalTo("WITHDRAWAL"))));
    }

    @Test
    void aBusinessRejectionIsNotAnError() throws InterruptedException {
        wireMock.stubFor(post(urlEqualTo("/accounts/acc-1/movements")).willReturn(aResponse()
                .withStatus(422)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {
                          "timestamp": "2026-09-24T15:24:00Z",
                          "status": 422,
                          "code": "INSUFFICIENT_FUNDS",
                          "message": "Not enough balance",
                          "path": "/accounts/acc-1/movements"
                        }
                        """)));

        TestObserver<MovementOutcome> observer = client.apply(new OperationId("op-1"), "acc-1",
                TransactionType.WITHDRAWAL, Money.of("150.00"), LocalDate.of(2026, 9, 24)).test();
        observer.await();

        observer.assertNoErrors();
        observer.assertValue(outcome -> !outcome.applied()
                && outcome.failureReason().code().equals("INSUFFICIENT_FUNDS"));
    }

    @Test
    void aResponseSlowerThanTwoSecondsBecomesDownstreamServiceUnavailable() throws InterruptedException {
        wireMock.stubFor(post(urlEqualTo("/accounts/acc-1/movements")).willReturn(aResponse()
                .withFixedDelay(3000)
                .withHeader("Content-Type", "application/json")
                .withBody("{}")));

        long start = System.nanoTime();
        TestObserver<MovementOutcome> observer = client.apply(new OperationId("op-1"), "acc-1",
                TransactionType.WITHDRAWAL, Money.of("150.00"), LocalDate.of(2026, 9, 24)).test();
        observer.await();
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        observer.assertError(DownstreamServiceUnavailableException.class);
        assertThat(elapsedMs).isLessThan(2900);
    }

    @Test
    void aServerErrorBecomesDownstreamServiceUnavailable() throws InterruptedException {
        wireMock.stubFor(post(urlEqualTo("/accounts/acc-1/movements")).willReturn(aResponse().withStatus(500)));

        TestObserver<MovementOutcome> observer = client.apply(new OperationId("op-1"), "acc-1",
                TransactionType.WITHDRAWAL, Money.of("150.00"), LocalDate.of(2026, 9, 24)).test();
        observer.await();

        observer.assertError(DownstreamServiceUnavailableException.class);
    }

    @Test
    void aReversalRejectionIsNotAnErrorEither() throws InterruptedException {
        wireMock.stubFor(post(urlEqualTo("/accounts/acc-1/movements/op-1/reversal")).willReturn(aResponse()
                .withStatus(422)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {
                          "timestamp": "2026-09-24T15:24:00Z",
                          "status": 422,
                          "code": "OPERATION_NOT_APPLIED",
                          "message": "Nothing to revert",
                          "path": "/accounts/acc-1/movements/op-1/reversal"
                        }
                        """)));

        TestObserver<MovementOutcome> observer = client.reverse(new OperationId("op-1"), "acc-1").test();
        observer.await();

        observer.assertNoErrors();
        observer.assertValue(outcome -> !outcome.applied()
                && outcome.failureReason().code().equals("OPERATION_NOT_APPLIED"));
    }

    @Test
    void anAppliedReversalHasNoFee() throws InterruptedException {
        wireMock.stubFor(post(urlEqualTo("/accounts/acc-1/movements/op-1/reversal")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"operationId": "op-1", "accountId": "acc-1", "newBalance": 1000.00}
                        """)));

        TestObserver<MovementOutcome> observer = client.reverse(new OperationId("op-1"), "acc-1").test();
        observer.await();

        observer.assertValue(MovementOutcome.applied(new OperationId("op-1"), Money.of("1000.00"), null));
    }

    @Test
    void theCircuitOpensAfterRepeatedTimeouts() throws InterruptedException {
        wireMock.stubFor(post(urlEqualTo("/accounts/acc-1/movements")).willReturn(aResponse().withFixedDelay(3000)));

        for (int i = 0; i < 4; i++) {
            client.apply(new OperationId("op-" + i), "acc-1", TransactionType.WITHDRAWAL, Money.of("10.00"),
                    LocalDate.of(2026, 9, 24)).test().await();
        }

        TestObserver<MovementOutcome> observer = client.apply(new OperationId("op-last"), "acc-1",
                TransactionType.WITHDRAWAL, Money.of("10.00"), LocalDate.of(2026, 9, 24)).test();
        observer.await();
        observer.assertError(DownstreamServiceUnavailableException.class);
    }
}
