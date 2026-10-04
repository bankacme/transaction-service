package com.bank.transaction.infrastructure.adapter.in.rest;

import com.bank.transaction.application.port.in.DiscardTransactionUseCase;
import com.bank.transaction.application.port.in.FindProductTransactionsUseCase;
import com.bank.transaction.application.port.in.FindTransactionUseCase;
import com.bank.transaction.application.port.in.FindTransactionsUseCase;
import com.bank.transaction.application.port.in.UpdateTransactionDescriptionUseCase;
import com.bank.transaction.domain.model.TransactionId;
import com.bank.transaction.infrastructure.adapter.in.rest.api.TransactionsApi;
import com.bank.transaction.infrastructure.adapter.in.rest.dto.TransactionPage;
import com.bank.transaction.infrastructure.adapter.in.rest.dto.TransactionStatus;
import com.bank.transaction.infrastructure.adapter.in.rest.dto.TransactionType;
import com.bank.transaction.infrastructure.adapter.in.rest.dto.UpdateDescriptionRequest;
import com.bank.transaction.infrastructure.mapper.TransactionRestMapper;
import com.bank.transaction.infrastructure.support.RxJavaReactorBridge;
import java.time.LocalDate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * {@code /products/{productId}/transactions}, {@code /transactions} and {@code
 * /transactions/{id}} (contract tag "Transactions"). Same security deferral note as {@link
 * DepositsAndWithdrawalsController}. {@code GET /transactions}'s required {@code customerId} is
 * enforced by the generated interface's own Bean Validation (query parameter marked {@code
 * required: true} in the contract) before this controller is even reached; {@code
 * FindTransactionsUseCaseImpl} re-checks it defensively (R3), which would surface as a plain
 * 500 if ever reached some other way — acceptable, since REST can't actually trigger it.
 *
 * <p>The REST DTO for a transaction shares its simple name ({@code Transaction}) with the
 * domain aggregate, same disambiguation as {@code TransfersController} — fully-qualified
 * references instead of an import.
 */
@RestController
public class TransactionsController implements TransactionsApi {

    private final FindProductTransactionsUseCase findProductTransactionsUseCase;
    private final FindTransactionsUseCase findTransactionsUseCase;
    private final FindTransactionUseCase findTransactionUseCase;
    private final UpdateTransactionDescriptionUseCase updateTransactionDescriptionUseCase;
    private final DiscardTransactionUseCase discardTransactionUseCase;
    private final TransactionRestMapper mapper;

    public TransactionsController(FindProductTransactionsUseCase findProductTransactionsUseCase,
            FindTransactionsUseCase findTransactionsUseCase, FindTransactionUseCase findTransactionUseCase,
            UpdateTransactionDescriptionUseCase updateTransactionDescriptionUseCase,
            DiscardTransactionUseCase discardTransactionUseCase, TransactionRestMapper mapper) {
        this.findProductTransactionsUseCase = findProductTransactionsUseCase;
        this.findTransactionsUseCase = findTransactionsUseCase;
        this.findTransactionUseCase = findTransactionUseCase;
        this.updateTransactionDescriptionUseCase = updateTransactionDescriptionUseCase;
        this.discardTransactionUseCase = discardTransactionUseCase;
        this.mapper = mapper;
    }

    @Override
    public Mono<ResponseEntity<TransactionPage>> listProductTransactions(String productId, LocalDate from,
            LocalDate to, TransactionType type, TransactionStatus status, Integer page, Integer size,
            ServerWebExchange exchange) {
        return RxJavaReactorBridge.toMono(findProductTransactionsUseCase.execute(productId,
                        mapper.toProductTransactionFilter(from, to, type, status), mapper.toPageRequest(page, size)))
                .map(mapper::toDto)
                .map(ResponseEntity::ok);
    }

    @Override
    public Mono<ResponseEntity<TransactionPage>> listTransactions(String customerId, LocalDate from, LocalDate to,
            TransactionType type, TransactionStatus status, Integer page, Integer size,
            ServerWebExchange exchange) {
        return RxJavaReactorBridge.toMono(findTransactionsUseCase.execute(
                mapper.toTransactionFilter(customerId, from, to, type, status), mapper.toPageRequest(page, size)))
                .map(mapper::toDto)
                .map(ResponseEntity::ok);
    }

    @Override
    public Mono<ResponseEntity<com.bank.transaction.infrastructure.adapter.in.rest.dto.Transaction>> getTransaction(
            String id, ServerWebExchange exchange) {
        return RxJavaReactorBridge.toMono(findTransactionUseCase.execute(new TransactionId(id)))
                .map(mapper::toDto)
                .map(ResponseEntity::ok);
    }

    @Override
    public Mono<ResponseEntity<com.bank.transaction.infrastructure.adapter.in.rest.dto.Transaction>>
            updateTransactionDescription(String id, Mono<UpdateDescriptionRequest> updateDescriptionRequest,
                    ServerWebExchange exchange) {
        return updateDescriptionRequest
                .map(request -> mapper.toCommand(id, request))
                .flatMap(command -> RxJavaReactorBridge.toMono(updateTransactionDescriptionUseCase.execute(command)))
                .map(mapper::toDto)
                .map(ResponseEntity::ok);
    }

    @Override
    public Mono<ResponseEntity<Void>> discardTransaction(String id, ServerWebExchange exchange) {
        return RxJavaReactorBridge.toMono(discardTransactionUseCase.execute(new TransactionId(id)))
                .thenReturn(ResponseEntity.noContent().build());
    }
}
