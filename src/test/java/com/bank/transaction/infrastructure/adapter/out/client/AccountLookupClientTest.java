package com.bank.transaction.infrastructure.adapter.out.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.bank.transaction.domain.exception.DownstreamServiceUnavailableException;
import com.bank.transaction.domain.model.AccountSnapshot;
import com.bank.transaction.domain.model.AccountStatus;
import com.bank.transaction.domain.model.AccountType;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.reactivex.rxjava3.observers.TestObserver;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * No Spring context, no Mongo — same approach as account-service's own
 * {@code CustomerServiceClientTest} (R7 there), which this class's setup mirrors.
 */
class AccountLookupClientTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private AccountLookupClient client;

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

        client = new AccountLookupClient(
                WebClient.builder(),
                wireMock.baseUrl(),
                CircuitBreakerRegistry.of(cbConfig),
                TimeLimiterRegistry.of(tlConfig));
    }

    @Test
    void aKnownAccountIsMappedFromTheResponseBody() throws InterruptedException {
        wireMock.stubFor(get(urlEqualTo("/accounts/acc-1")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {
                          "id": "acc-1",
                          "customerId": "cust-1",
                          "type": "SAVINGS",
                          "status": "ACTIVE",
                          "balance": 1000.00
                        }
                        """)));

        TestObserver<AccountSnapshot> observer = client.findById("acc-1").test();
        observer.await();

        observer.assertValue(new AccountSnapshot("acc-1", "cust-1", AccountType.SAVINGS, AccountStatus.ACTIVE));
    }

    @Test
    void aFourOhFourIsEmptyNotAnError() throws InterruptedException {
        wireMock.stubFor(get(urlEqualTo("/accounts/missing")).willReturn(aResponse().withStatus(404)));

        TestObserver<AccountSnapshot> observer = client.findById("missing").test();
        observer.await();

        observer.assertComplete();
        observer.assertNoValues();
        observer.assertNoErrors();
    }

    @Test
    void aResponseSlowerThanTwoSecondsBecomesDownstreamServiceUnavailable() throws InterruptedException {
        wireMock.stubFor(get(urlEqualTo("/accounts/acc-1")).willReturn(aResponse()
                .withFixedDelay(3000)
                .withHeader("Content-Type", "application/json")
                .withBody("{}")));

        long start = System.nanoTime();
        TestObserver<AccountSnapshot> observer = client.findById("acc-1").test();
        observer.await();
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        observer.assertError(DownstreamServiceUnavailableException.class);
        assertThat(elapsedMs).isLessThan(2900);
    }

    @Test
    void aServerErrorBecomesDownstreamServiceUnavailable() throws InterruptedException {
        wireMock.stubFor(get(urlEqualTo("/accounts/acc-1")).willReturn(aResponse().withStatus(500)));

        TestObserver<AccountSnapshot> observer = client.findById("acc-1").test();
        observer.await();

        observer.assertError(DownstreamServiceUnavailableException.class);
    }

    @Test
    void theCircuitOpensAfterRepeatedTimeouts() throws InterruptedException {
        wireMock.stubFor(get(urlEqualTo("/accounts/acc-1")).willReturn(aResponse().withFixedDelay(3000)));

        for (int i = 0; i < 4; i++) {
            client.findById("acc-1").test().await();
        }

        TestObserver<AccountSnapshot> observer = client.findById("acc-1").test();
        observer.await();
        observer.assertError(DownstreamServiceUnavailableException.class);
    }
}
