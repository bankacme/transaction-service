package com.bank.transaction.infrastructure.adapter.in.rest;

import com.bank.transaction.application.port.in.RecordExternalMovementUseCase;
import com.bank.transaction.application.port.in.RecoverPendingOperationsUseCase;
import com.bank.transaction.infrastructure.adapter.in.rest.api.InternalApi;
import com.bank.transaction.infrastructure.adapter.in.rest.dto.RecordExternalMovementRequest;
import com.bank.transaction.infrastructure.adapter.in.rest.dto.RecoveryResult;
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
 * {@code /transactions/records} and {@code /transaction-recovery-runs} (contract tag
 * "Internal", {@code x-gateway-internal: true}): not published on the Gateway, matching account-
 * service's own "Internal movements" controller. {@code recordExternalMovement} is used by
 * {@code credit-service} in P1/P2; {@code runTransactionRecovery} is the on-demand twin of the
 * scheduled recovery sweep (R6 wires the actual schedule).
 */
@RestController
public class InternalController implements InternalApi {

    private final RecordExternalMovementUseCase recordExternalMovementUseCase;
    private final RecoverPendingOperationsUseCase recoverPendingOperationsUseCase;
    private final TransactionRestMapper mapper;
    private final Clock clock;

    public InternalController(RecordExternalMovementUseCase recordExternalMovementUseCase,
            RecoverPendingOperationsUseCase recoverPendingOperationsUseCase, TransactionRestMapper mapper,
            Clock clock) {
        this.recordExternalMovementUseCase = recordExternalMovementUseCase;
        this.recoverPendingOperationsUseCase = recoverPendingOperationsUseCase;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public Mono<ResponseEntity<com.bank.transaction.infrastructure.adapter.in.rest.dto.Transaction>>
            recordExternalMovement(Mono<RecordExternalMovementRequest> recordExternalMovementRequest,
                    ServerWebExchange exchange) {
        Instant requestReceivedAt = clock.instant();
        return recordExternalMovementRequest
                .map(mapper::toCommand)
                .flatMap(command -> RxJavaReactorBridge.toMono(recordExternalMovementUseCase.execute(command)))
                .map(tx -> {
                    HttpStatus status = mapper.wasCreatedByThisRequest(tx.createdAt(), requestReceivedAt)
                            ? HttpStatus.CREATED
                            : HttpStatus.OK;
                    return ResponseEntity.status(status).body(mapper.toDto(tx));
                });
    }

    @Override
    public Mono<ResponseEntity<RecoveryResult>> runTransactionRecovery(Integer olderThanMinutes,
                                                                          ServerWebExchange exchange) {
        return RxJavaReactorBridge.toMono(recoverPendingOperationsUseCase.execute(olderThanMinutes))
                .map(result -> ResponseEntity.ok(mapper.toDto(result, olderThanMinutes, clock.instant())));
    }
}
