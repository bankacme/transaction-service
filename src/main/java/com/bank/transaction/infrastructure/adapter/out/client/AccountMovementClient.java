package com.bank.transaction.infrastructure.adapter.out.client;

import com.bank.transaction.application.port.out.AccountMovementPort;
import com.bank.transaction.domain.exception.DownstreamServiceUnavailableException;
import com.bank.transaction.domain.model.FailureReason;
import com.bank.transaction.domain.model.Money;
import com.bank.transaction.domain.model.MovementOutcome;
import com.bank.transaction.domain.model.OperationId;
import com.bank.transaction.domain.model.TransactionType;
import com.bank.transaction.infrastructure.support.RxJavaReactorBridge;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.timelimiter.TimeLimiterOperator;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.reactivex.rxjava3.core.Single;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * R7's real {@link AccountMovementPort} — replaces R6's {@code NoOpAccountMovementAdapter}.
 * {@code POST /accounts/{id}/movements} ({@link #apply}) and {@code POST
 * /accounts/{id}/movements/{operationId}/reversal} ({@link #reverse}) on account-service.
 *
 * <p><b>Key design decision, different from account-service's own {@code CustomerServiceClient}
 * (R7 there) and from {@link AccountLookupClient} above</b>: a business rejection — any HTTP
 * error response whose body is the contract's {@code ErrorResponse} shape ({@code code} +
 * {@code message}; account-service's documented 404 {@code ACCOUNT_NOT_FOUND}/{@code
 * OPERATION_NOT_FOUND} and 422 codes such as {@code INSUFFICIENT_FUNDS} or {@code
 * OPERATION_NOT_APPLIED}) is translated into a normal, successful {@link MovementOutcome}
 * ({@link MovementOutcome#rejected}), never into a thrown exception or a circuit-breaker
 * failure. Two independent reasons force this, not just one:
 * <ul>
 *   <li>{@code AbstractRegisterMovementUseCase}/{@code RegisterDeposit/WithdrawalUseCaseImpl}
 *       (R3) already treat a rejected {@link MovementOutcome} as the normal path for {@link
 *       #apply} — a 422 is exactly what a declined withdrawal looks like.</li>
 *   <li>{@code StartTransferUseCaseImpl#reverseDebit} (R3) calls {@link #reverse} with
 *       <em>no</em> {@code onErrorResumeNext}/error handling at all around it — it only ever
 *       inspects {@code reversalOutcome.applied()}/{@code .failureReason()} on the emitted
 *       value. If this client let a 404/422 escape as a thrown exception instead, it would
 *       blow up the whole compensation flow uncaught — after the compensating {@link
 *       com.bank.transaction.domain.model.Transfer} was already saved — instead of recording
 *       "reversal attempt failed, try again later" the way {@code
 *       RecoverPendingOperationsUseCaseImpl} (R3/R4) is explicitly built to retry.</li>
 * </ul>
 * Only a response that does <em>not</em> carry that shape (a timeout, an open circuit, a 5xx
 * without a JSON body, a malformed body, 401/403/409 — none of them expected from a
 * same-trust-boundary internal call we built ourselves) becomes {@link
 * DownstreamServiceUnavailableException}, and only those count as a circuit-breaker failure —
 * same "a documented non-2xx is not automatically a breaker failure" principle account-service
 * already applies to its own 404.
 *
 * <p>{@code type} translation ({@link AccountMovementPort}'s own javadoc, R3): account-service
 * only understands {@code DEPOSIT}/{@code WITHDRAWAL} ({@code MovementType} in its contract);
 * {@link TransactionType#TRANSFER_OUT} debits the source account like a withdrawal and {@link
 * TransactionType#TRANSFER_IN} credits the destination like a deposit, so {@link
 * #toMovementType} maps those two down to the two account-service understands. Any other
 * {@link TransactionType} reaching this adapter (FEE, CREDIT_PAYMENT, ...) is a programming
 * error — {@link AccountMovementPort} is never called for those in P1/P2 — so it fails fast
 * with {@link IllegalArgumentException} rather than silently mis-translating it.
 */
@Component
public class AccountMovementClient implements AccountMovementPort {

    private static final String SERVICE_NAME = "account-service";

    private final WebClient webClient;
    private final CircuitBreaker circuitBreaker;
    private final TimeLimiter timeLimiter;

    public AccountMovementClient(@LoadBalanced WebClient.Builder builder,
                                  @Value("${bank.clients.account-service.base-url}") String baseUrl,
                                  CircuitBreakerRegistry circuitBreakerRegistry,
                                  TimeLimiterRegistry timeLimiterRegistry) {
        this.webClient = builder.baseUrl(baseUrl).build();
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(SERVICE_NAME);
        this.timeLimiter = timeLimiterRegistry.timeLimiter(SERVICE_NAME);
    }

    @Override
    public Single<MovementOutcome> apply(OperationId operationId, String accountId, TransactionType type,
                                          Money amount, LocalDate date) {
        ApplyMovementRequest request = new ApplyMovementRequest(operationId.value(), toMovementType(type),
                amount.amount(), date);
        Mono<MovementOutcome> call = webClient.post()
                .uri("/accounts/{accountId}/movements", accountId)
                .bodyValue(request)
                .exchangeToMono(response -> handleMovementResponse(response, operationId))
                .transformDeferred(TimeLimiterOperator.of(timeLimiter))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .onErrorMap(AccountMovementClient::wrapUnlessAlreadyDownstream);
        return RxJavaReactorBridge.toSingle(call);
    }

    @Override
    public Single<MovementOutcome> reverse(OperationId operationId, String accountId) {
        Mono<MovementOutcome> call = webClient.post()
                .uri("/accounts/{accountId}/movements/{operationId}/reversal", accountId, operationId.value())
                .exchangeToMono(response -> handleReversalResponse(response, operationId))
                .transformDeferred(TimeLimiterOperator.of(timeLimiter))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .onErrorMap(AccountMovementClient::wrapUnlessAlreadyDownstream);
        return RxJavaReactorBridge.toSingle(call);
    }

    private Mono<MovementOutcome> handleMovementResponse(ClientResponse response, OperationId operationId) {
        if (response.statusCode().is2xxSuccessful()) {
            return response.bodyToMono(MovementResult.class).map(r -> toApplied(operationId, r.fee(), r.newBalance()));
        }
        if (isBusinessRejection(response.statusCode())) {
            return response.bodyToMono(ErrorResponse.class).map(e -> toRejected(operationId, e));
        }
        return response.createError();
    }

    private Mono<MovementOutcome> handleReversalResponse(ClientResponse response, OperationId operationId) {
        if (response.statusCode().is2xxSuccessful()) {
            return response.bodyToMono(ReversalResult.class)
                    .map(r -> toApplied(operationId, null, r.newBalance()));
        }
        if (isBusinessRejection(response.statusCode())) {
            return response.bodyToMono(ErrorResponse.class).map(e -> toRejected(operationId, e));
        }
        return response.createError();
    }

    /** 404 ({@code ACCOUNT_NOT_FOUND}/{@code OPERATION_NOT_FOUND}) and 422 (every business
     *  rule in the ficha's list) both carry the contract's {@code ErrorResponse} body — see
     *  the class javadoc for why both become a rejected outcome here, never a thrown
     *  exception. */
    private static boolean isBusinessRejection(HttpStatusCode status) {
        return status.equals(HttpStatus.NOT_FOUND) || status.equals(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    private static MovementOutcome toApplied(OperationId operationId, BigDecimal fee, BigDecimal newBalance) {
        return MovementOutcome.applied(operationId, Money.of(newBalance), fee == null ? null : Money.of(fee));
    }

    private static MovementOutcome toRejected(OperationId operationId, ErrorResponse error) {
        return MovementOutcome.rejected(operationId, new FailureReason(error.code(), error.message()));
    }

    /** Everything that reaches here is a transport-class failure (timeout, open circuit, a
     *  5xx or malformed body via {@code response.createError()}) — a real business rejection
     *  never becomes an error signal in the first place (see the class javadoc), so this never
     *  actually receives a {@link DownstreamServiceUnavailableException} to pass through
     *  unchanged; it only guards against double-wrapping if that ever changes. */
    private static Throwable wrapUnlessAlreadyDownstream(Throwable ex) {
        return ex instanceof DownstreamServiceUnavailableException ? ex
                : new DownstreamServiceUnavailableException(SERVICE_NAME, ex);
    }

    private static String toMovementType(TransactionType type) {
        return switch (type) {
            case DEPOSIT, TRANSFER_IN -> "DEPOSIT";
            case WITHDRAWAL, TRANSFER_OUT -> "WITHDRAWAL";
            default -> throw new IllegalArgumentException(
                    "AccountMovementPort does not support " + type + " in P1/P2");
        };
    }

    private record ApplyMovementRequest(String operationId, String type, BigDecimal amount, LocalDate date) {
    }

    /** Only {@code fee}/{@code newBalance}: {@code accountId}/{@code type}/{@code
     *  movementNumber} aren't part of {@link MovementOutcome}. */
    private record MovementResult(BigDecimal fee, BigDecimal newBalance) {
    }

    /** Only {@code newBalance}: a reversal has no fee of its own. */
    private record ReversalResult(BigDecimal newBalance) {
    }

    private record ErrorResponse(String code, String message) {
    }
}
