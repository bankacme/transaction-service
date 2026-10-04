package com.bank.transaction.application.usecase;

import com.bank.transaction.application.port.in.ProductTransactionFilter;
import com.bank.transaction.application.port.in.TransactionFilter;
import com.bank.transaction.application.port.out.TransactionRepositoryPort;
import com.bank.transaction.application.view.PageRequest;
import com.bank.transaction.application.view.PageView;
import com.bank.transaction.domain.model.OperationId;
import com.bank.transaction.domain.model.Transaction;
import com.bank.transaction.domain.model.TransactionId;
import com.bank.transaction.domain.model.TransactionStatus;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** In-memory stand-in for the Mongo adapter (R4), reusable across use-case tests. Not
 *  thread-safe on purpose: tests run single-threaded. */
public class InMemoryTransactionRepository implements TransactionRepositoryPort {

    private final Map<String, Transaction> byId = new LinkedHashMap<>();

    @Override
    public Single<Transaction> save(Transaction transaction) {
        byId.put(transaction.id().value(), transaction);
        return Single.just(transaction);
    }

    @Override
    public Maybe<Transaction> findById(TransactionId id) {
        Transaction found = byId.get(id.value());
        return found == null ? Maybe.empty() : Maybe.just(found);
    }

    @Override
    public Maybe<Transaction> findByOperationId(OperationId operationId) {
        return byId.values().stream()
                .filter(tx -> tx.operationId().equals(operationId))
                .findFirst()
                .map(Maybe::just)
                .orElseGet(Maybe::empty);
    }

    @Override
    public Single<PageView<Transaction>> findByProduct(String productId, ProductTransactionFilter filter,
                                                         PageRequest page) {
        List<Transaction> matches = byId.values().stream()
                .filter(tx -> tx.product().productId().equals(productId))
                .filter(tx -> filter.type() == null || filter.type() == tx.type())
                .filter(tx -> filter.status() == null || filter.status() == tx.status())
                .sorted(Comparator.comparing(Transaction::occurredAt).reversed())
                .toList();
        return Single.just(paginate(matches, page));
    }

    @Override
    public Single<PageView<Transaction>> findAll(TransactionFilter filter, PageRequest page) {
        List<Transaction> matches = byId.values().stream()
                .filter(tx -> filter.customerId() == null || filter.customerId().equals(tx.customerId()))
                .filter(tx -> filter.type() == null || filter.type() == tx.type())
                .filter(tx -> filter.status() == null || filter.status() == tx.status())
                .sorted(Comparator.comparing(Transaction::occurredAt).reversed())
                .toList();
        return Single.just(paginate(matches, page));
    }

    @Override
    public Flowable<Transaction> findPendingOlderThan(Instant threshold) {
        return Flowable.fromIterable(byId.values().stream()
                .filter(tx -> tx.status() == TransactionStatus.PENDING)
                .filter(tx -> tx.createdAt().isBefore(threshold))
                .toList());
    }

    public int size() {
        return byId.size();
    }

    private static PageView<Transaction> paginate(List<Transaction> all, PageRequest page) {
        int from = Math.min(page.page() * page.size(), all.size());
        int to = Math.min(from + page.size(), all.size());
        return PageView.of(all.subList(from, to), page, all.size());
    }
}
