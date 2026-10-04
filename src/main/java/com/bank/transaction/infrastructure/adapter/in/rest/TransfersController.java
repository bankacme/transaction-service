package com.bank.transaction.infrastructure.adapter.in.rest;

import com.bank.transaction.application.port.in.FindTransferUseCase;
import com.bank.transaction.application.port.in.FindTransfersUseCase;
import com.bank.transaction.application.port.in.StartTransferUseCase;
import com.bank.transaction.domain.model.Transfer;
import com.bank.transaction.domain.model.TransferId;
import com.bank.transaction.infrastructure.adapter.in.rest.api.TransfersApi;
import com.bank.transaction.infrastructure.adapter.in.rest.dto.StartTransferRequest;
import com.bank.transaction.infrastructure.adapter.in.rest.dto.TransferStatus;
import com.bank.transaction.infrastructure.mapper.TransactionRestMapper;
import com.bank.transaction.infrastructure.support.RxJavaReactorBridge;
import java.time.Clock;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * {@code /transfers} and {@code /transfers/{id}} (contract tag "Transfers"). Same security
 * deferral note as {@link DepositsAndWithdrawalsController}.
 *
 * <p>The REST DTO for a transfer shares its simple name ({@code Transfer}) with the domain
 * aggregate — same disambiguation problem account-service's own mapper documents — so every
 * reference to the DTO type below uses its fully-qualified name instead of an import, exactly
 * like {@code AccountRestMapper} does.
 */
@RestController
public class TransfersController implements TransfersApi {

    private final StartTransferUseCase startTransferUseCase;
    private final FindTransfersUseCase findTransfersUseCase;
    private final FindTransferUseCase findTransferUseCase;
    private final TransactionRestMapper mapper;
    private final Clock clock;

    public TransfersController(StartTransferUseCase startTransferUseCase, FindTransfersUseCase findTransfersUseCase,
            FindTransferUseCase findTransferUseCase, TransactionRestMapper mapper, Clock clock) {
        this.startTransferUseCase = startTransferUseCase;
        this.findTransfersUseCase = findTransfersUseCase;
        this.findTransferUseCase = findTransferUseCase;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public Mono<ResponseEntity<com.bank.transaction.infrastructure.adapter.in.rest.dto.Transfer>> startTransfer(
            Mono<StartTransferRequest> startTransferRequest, ServerWebExchange exchange) {
        Instant requestReceivedAt = clock.instant();
        return startTransferRequest
                .map(mapper::toCommand)
                .flatMap(command -> RxJavaReactorBridge.toMono(startTransferUseCase.execute(command)))
                .flatMap(transfer -> toResponseEntity(transfer, requestReceivedAt));
    }

    @Override
    public Mono<ResponseEntity<Flux<com.bank.transaction.infrastructure.adapter.in.rest.dto.Transfer>>> listTransfers(
            String operationId, String accountId, TransferStatus status, ServerWebExchange exchange) {
        Flux<com.bank.transaction.infrastructure.adapter.in.rest.dto.Transfer> transfers = RxJavaReactorBridge
                .toFlux(findTransfersUseCase.execute(mapper.toTransferFilter(operationId, accountId, status)))
                .map(mapper::toDto);
        return Mono.just(ResponseEntity.ok(transfers));
    }

    @Override
    public Mono<ResponseEntity<com.bank.transaction.infrastructure.adapter.in.rest.dto.Transfer>> getTransfer(
            String id, ServerWebExchange exchange) {
        return RxJavaReactorBridge.toMono(findTransferUseCase.execute(new TransferId(id)))
                .map(mapper::toDto)
                .map(ResponseEntity::ok);
    }

    /** Terminal-without-moving-money ({@code FAILED}) or compensated ({@code COMPENSATED}) ->
     *  a {@link TransferRejectedException} error signal. Still in progress ({@code STARTED},
     *  {@code SOURCE_DEBITED}, {@code COMPENSATING}) or needing manual review ({@code
     *  COMPENSATION_FAILED}) -> 202. {@code COMPLETED} -> 201/200, same new-vs-replay rule as
     *  {@link DepositsAndWithdrawalsController}. */
    private Mono<ResponseEntity<com.bank.transaction.infrastructure.adapter.in.rest.dto.Transfer>> toResponseEntity(
            Transfer transfer, Instant requestReceivedAt) {
        com.bank.transaction.infrastructure.adapter.in.rest.dto.Transfer body = mapper.toDto(transfer);
        return switch (transfer.status()) {
            case FAILED, COMPENSATED -> Mono.error(new TransferRejectedException(transfer));
            case STARTED, SOURCE_DEBITED, COMPENSATING, COMPENSATION_FAILED ->
                    Mono.just(ResponseEntity.status(HttpStatus.ACCEPTED).body(body));
            case COMPLETED -> {
                HttpStatus status = mapper.wasCreatedByThisRequest(transfer.createdAt(), requestReceivedAt)
                        ? HttpStatus.CREATED
                        : HttpStatus.OK;
                yield Mono.just(ResponseEntity.status(status).body(body));
            }
        };
    }
}
