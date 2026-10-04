package com.bank.transaction.infrastructure.adapter.in.rest;

import com.bank.transaction.application.port.in.RegisterDepositUseCase;
import com.bank.transaction.application.port.in.RegisterWithdrawalUseCase;
import com.bank.transaction.domain.model.Transaction;
import com.bank.transaction.infrastructure.adapter.in.rest.api.DepositsAndWithdrawalsApi;
import com.bank.transaction.infrastructure.adapter.in.rest.dto.MovementRequest;
import com.bank.transaction.infrastructure.adapter.in.rest.dto.MovementResponse;
import com.bank.transaction.infrastructure.mapper.TransactionRestMapper;
import com.bank.transaction.infrastructure.support.RxJavaReactorBridge;
import java.time.Clock;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * {@code /deposits} and {@code /withdrawals} (contract tag "Deposits and withdrawals"). Role/
 * customer-scope enforcement ({@code x-roles}, {@code x-customer-scope}) is deferred to when
 * {@code security.enabled} actually exists (P3, {@code auth-service}) — nothing here checks a
 * token yet, same deferral as account-service's own controllers.
 *
 * <p>Both operations share the exact same response-shaping: see {@link
 * #toResponseEntity(Transaction, Instant)}. A rejected movement ({@code status=FAILED}) isn't an
 * exception from the use case's point of view (see {@link MovementRejectedException}), so it's
 * translated here, not caught from upstream.
 */
@RestController
public class DepositsAndWithdrawalsController implements DepositsAndWithdrawalsApi {

    private final RegisterDepositUseCase registerDepositUseCase;
    private final RegisterWithdrawalUseCase registerWithdrawalUseCase;
    private final TransactionRestMapper mapper;
    private final Clock clock;

    public DepositsAndWithdrawalsController(RegisterDepositUseCase registerDepositUseCase,
            RegisterWithdrawalUseCase registerWithdrawalUseCase, TransactionRestMapper mapper, Clock clock) {
        this.registerDepositUseCase = registerDepositUseCase;
        this.registerWithdrawalUseCase = registerWithdrawalUseCase;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public Mono<ResponseEntity<MovementResponse>> registerDeposit(Mono<MovementRequest> movementRequest,
                                                                     ServerWebExchange exchange) {
        Instant requestReceivedAt = clock.instant();
        return movementRequest
                .map(mapper::toCommand)
                .flatMap(command -> RxJavaReactorBridge.toMono(registerDepositUseCase.execute(command)))
                .flatMap(tx -> toResponseEntity(tx, requestReceivedAt));
    }

    @Override
    public Mono<ResponseEntity<MovementResponse>> registerWithdrawal(Mono<MovementRequest> movementRequest,
                                                                        ServerWebExchange exchange) {
        Instant requestReceivedAt = clock.instant();
        return movementRequest
                .map(mapper::toCommand)
                .flatMap(command -> RxJavaReactorBridge.toMono(registerWithdrawalUseCase.execute(command)))
                .flatMap(tx -> toResponseEntity(tx, requestReceivedAt));
    }

    /** {@code FAILED} -&gt; a {@link MovementRejectedException} error signal (see its own
     *  javadoc for why). {@code PENDING} -&gt; 202, always (repeating a still-pending request
     *  returns 202 again, same contract text for both the first and a replayed call). {@code
     *  COMPLETED} -&gt; 201 if this very call is what completed it, 200 if it's a replay of an
     *  already-completed movement — see {@code TransactionRestMapper.wasCreatedByThisRequest}. */
    private Mono<ResponseEntity<MovementResponse>> toResponseEntity(Transaction tx, Instant requestReceivedAt) {
        MovementResponse body = mapper.toMovementResponseDto(tx);
        return switch (tx.status()) {
            case FAILED -> Mono.error(new MovementRejectedException(tx));
            case PENDING -> Mono.just(ResponseEntity.status(HttpStatus.ACCEPTED).body(body));
            case COMPLETED -> {
                HttpStatus status = mapper.wasCreatedByThisRequest(tx.createdAt(), requestReceivedAt)
                        ? HttpStatus.CREATED
                        : HttpStatus.OK;
                yield Mono.just(ResponseEntity.status(status).body(body));
            }
            default -> Mono.error(new IllegalStateException(
                    "A freshly registered movement cannot be " + tx.status()));
        };
    }
}
