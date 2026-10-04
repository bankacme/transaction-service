package com.bank.transaction.infrastructure.adapter.in.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import com.bank.transaction.application.port.in.DiscardTransactionUseCase;
import com.bank.transaction.application.port.in.FindProductTransactionsUseCase;
import com.bank.transaction.application.port.in.FindTransactionUseCase;
import com.bank.transaction.application.port.in.FindTransactionsUseCase;
import com.bank.transaction.application.port.in.UpdateTransactionDescriptionUseCase;
import com.bank.transaction.application.view.PageRequest;
import com.bank.transaction.application.view.PageView;
import com.bank.transaction.domain.exception.BusinessRuleViolationException;
import com.bank.transaction.domain.exception.TransactionNotFoundException;
import com.bank.transaction.domain.model.Transaction;
import com.bank.transaction.domain.model.TransactionId;
import com.bank.transaction.infrastructure.fixture.TransactionFixtures;
import com.bank.transaction.infrastructure.mapper.TransactionRestMapper;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

/** {@code /products/{productId}/transactions}, {@code /transactions} and {@code
 *  /transactions/{id}}. */
@WebFluxTest(TransactionsController.class)
@Import(TransactionRestMapper.class)
class TransactionsControllerTest {

    @Autowired
    private WebTestClient client;

    @MockitoBean
    private FindProductTransactionsUseCase findProductTransactionsUseCase;
    @MockitoBean
    private FindTransactionsUseCase findTransactionsUseCase;
    @MockitoBean
    private FindTransactionUseCase findTransactionUseCase;
    @MockitoBean
    private UpdateTransactionDescriptionUseCase updateTransactionDescriptionUseCase;
    @MockitoBean
    private DiscardTransactionUseCase discardTransactionUseCase;

    @Test
    void listProductTransactionsReturnsAPage() {
        Transaction tx = TransactionFixtures.completedWithdrawal("op-1", "acc-1", "cust-A", "100.00", "900.00");
        PageView<Transaction> page = PageView.of(List.of(tx), new PageRequest(0, 20), 1);
        given(findProductTransactionsUseCase.execute(eq("acc-1"), any(), any())).willReturn(Single.just(page));

        client.get().uri("/api/v1/products/acc-1/transactions")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.totalElements").isEqualTo(1)
                .jsonPath("$.content[0].id").isEqualTo(tx.id().value());
    }

    @Test
    void listProductTransactionsWithFromAfterToReturns400InvalidDateRange() {
        client.get().uri("/api/v1/products/acc-1/transactions?from=2026-09-30&to=2026-09-01")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("INVALID_DATE_RANGE");
    }

    @Test
    void listTransactionsReturnsAPageForTheGivenCustomer() {
        Transaction tx = TransactionFixtures.completedWithdrawal("op-1", "acc-1", "cust-A", "100.00", "900.00");
        PageView<Transaction> page = PageView.of(List.of(tx), new PageRequest(0, 20), 1);
        given(findTransactionsUseCase.execute(any(), any())).willReturn(Single.just(page));

        client.get().uri("/api/v1/transactions?customerId=cust-A")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.content[0].customerId").isEqualTo("cust-A");
    }

    @Test
    void getTransactionReturnsTheTransaction() {
        Transaction tx = TransactionFixtures.completedWithdrawal("op-1", "acc-1", "cust-A", "100.00", "900.00");
        given(findTransactionUseCase.execute(eq(tx.id()))).willReturn(Single.just(tx));

        client.get().uri("/api/v1/transactions/{id}", tx.id().value())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.amount").isEqualTo(100.00);
    }

    @Test
    void getTransactionThatDoesNotExistReturns404() {
        given(findTransactionUseCase.execute(eq(new TransactionId("missing"))))
                .willReturn(Single.error(new TransactionNotFoundException("missing")));

        client.get().uri("/api/v1/transactions/missing")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo("TRANSACTION_NOT_FOUND");
    }

    @Test
    void updateTransactionDescriptionReturns200WithTheUpdatedTransaction() {
        Transaction tx = TransactionFixtures.completedWithdrawal("op-1", "acc-1", "cust-A", "100.00", "900.00")
                .updateDescription("nueva descripcion", TransactionFixtures.CLOCK);
        given(updateTransactionDescriptionUseCase.execute(any())).willReturn(Single.just(tx));

        client.put().uri("/api/v1/transactions/{id}", tx.id().value())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "description": "nueva descripcion"
                        }
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.description").isEqualTo("nueva descripcion");
    }

    @Test
    void updatingADiscardedTransactionsDescriptionReturns422() {
        given(updateTransactionDescriptionUseCase.execute(any())).willReturn(Single.error(
                new BusinessRuleViolationException("TRANSACTION_DISCARDED", "fue descartado")));

        client.put().uri("/api/v1/transactions/{id}", "tx-1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{ \"description\": \"x\" }")
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("TRANSACTION_DISCARDED");
    }

    @Test
    void discardTransactionReturns204() {
        given(discardTransactionUseCase.execute(any())).willReturn(Completable.complete());

        client.delete().uri("/api/v1/transactions/{id}", "tx-1")
                .exchange()
                .expectStatus().isNoContent()
                .expectBody().isEmpty();
    }

    @Test
    void discardingATransactionThatIsNotFailedReturns422() {
        given(discardTransactionUseCase.execute(any())).willReturn(Completable.error(
                new BusinessRuleViolationException("NOT_DISCARDABLE", "no esta FAILED")));

        client.delete().uri("/api/v1/transactions/{id}", "tx-1")
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("NOT_DISCARDABLE");
    }
}
