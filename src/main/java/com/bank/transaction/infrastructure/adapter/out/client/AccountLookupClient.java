package com.bank.transaction.infrastructure.adapter.out.client;

import com.bank.transaction.application.port.out.AccountLookupPort;
import com.bank.transaction.domain.exception.DownstreamServiceUnavailableException;
import com.bank.transaction.domain.model.AccountSnapshot;
import com.bank.transaction.domain.model.AccountStatus;
import com.bank.transaction.domain.model.AccountType;
import com.bank.transaction.infrastructure.support.RxJavaReactorBridge;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.timelimiter.TimeLimiterOperator;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.reactivex.rxjava3.core.Maybe;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * R7's real {@link AccountLookupPort} — replaces R6's {@code NoOpAccountLookupAdapter}.
 * {@code GET /accounts/{id}} on account-service; same shape as account-service's own
 * {@code CustomerServiceClient} (R7 there): a 404 becomes {@link Maybe#empty()} (the port's
 * own contract — "empty means the account doesn't exist" — is a normal outcome the circuit
 * breaker must NOT count as a failure), anything else that goes wrong (timeout, open circuit,
 * 5xx, a malformed body) becomes {@link DownstreamServiceUnavailableException}. Shares the
 * {@code account-service} {@link CircuitBreaker}/{@link TimeLimiter} instance with {@link
 * AccountMovementClient} — one budget, one breaker, for every call to the same downstream
 * service, exactly like account-service shares one {@code customer-service} instance across
 * its own single client.
 */
@Component
public class AccountLookupClient implements AccountLookupPort {

    private final WebClient webClient;
    private final CircuitBreaker circuitBreaker;
    private final TimeLimiter timeLimiter;

    public AccountLookupClient(WebClient.Builder builder,
                                @Value("${bank.clients.account-service.base-url}") String baseUrl,
                                CircuitBreakerRegistry circuitBreakerRegistry,
                                TimeLimiterRegistry timeLimiterRegistry) {
        this.webClient = builder.baseUrl(baseUrl).build();
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("account-service");
        this.timeLimiter = timeLimiterRegistry.timeLimiter("account-service");
    }

    @Override
    public Maybe<AccountSnapshot> findById(String accountId) {
        Mono<AccountSnapshot> call = webClient.get()
                .uri("/accounts/{id}", accountId)
                .exchangeToMono(response -> {
                    if (response.statusCode().equals(HttpStatus.NOT_FOUND)) {
                        return Mono.<AccountResponse>empty();
                    }
                    if (response.statusCode().isError()) {
                        return response.<AccountResponse>createError();
                    }
                    return response.bodyToMono(AccountResponse.class);
                })
                .map(AccountLookupClient::toSnapshot)
                .transformDeferred(TimeLimiterOperator.of(timeLimiter))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .onErrorMap(ex -> new DownstreamServiceUnavailableException("account-service", ex));
        return RxJavaReactorBridge.toMaybe(call);
    }

    private static AccountSnapshot toSnapshot(AccountResponse response) {
        return new AccountSnapshot(response.id(), response.customerId(), response.type(), response.status());
    }

    /** Only the fields {@link AccountSnapshot} needs; account-service's {@code Account} schema
     *  has many more (balance, conditions, holders, signers, timestamps...) that this call
     *  ignores — same restraint as {@code CustomerServiceClient}'s own {@code CustomerResponse}. */
    private record AccountResponse(String id, String customerId, AccountType type, AccountStatus status) {
    }
}
