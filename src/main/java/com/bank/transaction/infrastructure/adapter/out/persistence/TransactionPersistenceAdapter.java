package com.bank.transaction.infrastructure.adapter.out.persistence;

import com.bank.transaction.application.port.in.ProductTransactionFilter;
import com.bank.transaction.application.port.in.TransactionFilter;
import com.bank.transaction.application.port.out.TransactionRepositoryPort;
import com.bank.transaction.application.view.PageRequest;
import com.bank.transaction.application.view.PageView;
import com.bank.transaction.domain.model.DateRange;
import com.bank.transaction.domain.model.OperationId;
import com.bank.transaction.domain.model.Transaction;
import com.bank.transaction.domain.model.TransactionId;
import com.bank.transaction.domain.model.TransactionStatus;
import com.bank.transaction.infrastructure.mapper.TransactionDocumentMapper;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.time.Instant;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Range;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/**
 * The four-combination dispatch data-model.md section 4 prescribes: {@code type} and {@code
 * status} are each independently optional on the history filters, so rather than one dynamic
 * query this picks one of four {@link TransactionMongoRepository} method pairs per call —
 * once for {@link #findByProduct} and once more for {@link #findAll} (the admin,
 * customerId-scoped query) — exactly the eight pairs that repository declares. When {@code
 * status} is absent, {@code DISCARDED} movements are excluded by default (a soft-deleted
 * movement shouldn't clutter a history nobody explicitly asked to include it in); asking for
 * {@code status=DISCARDED} explicitly still works since that branch has no such exclusion.
 *
 * <p>Sort is fixed to {@code occurredAt} descending (data-model.md section 4: "Sort fijo"),
 * never taken from the caller.
 */
@Component
public class TransactionPersistenceAdapter implements TransactionRepositoryPort {

    /** {@code Instant.MAX} (year 1,000,000,000) blows up with an {@code ArithmeticException:
     *  long overflow} the moment Spring Data Mongo's driver converts it to a {@code
     *  java.util.Date} to serialize the query — confirmed against a real Mongo. This is a
     *  plain, safely-representable "far enough in the future" stand-in for "no upper bound"
     *  instead, used by {@link #rangeOf(DateRange)} when no {@code to} was given. */
    private static final Instant OPEN_ENDED_UPPER_BOUND = Instant.parse("9999-12-31T23:59:59.999999999Z");

    private final TransactionMongoRepository repository;
    private final TransactionDocumentMapper mapper;

    public TransactionPersistenceAdapter(TransactionMongoRepository repository, TransactionDocumentMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Override
    public Single<Transaction> save(Transaction transaction) {
        return repository.save(mapper.toDocument(transaction))
                .map(mapper::toDomain)
                .onErrorResumeNext(error -> recoverFromOperationIdRace(transaction.operationId(), error));
    }

    @Override
    public Maybe<Transaction> findById(TransactionId id) {
        return repository.findById(id.value()).map(mapper::toDomain);
    }

    @Override
    public Maybe<Transaction> findByOperationId(OperationId operationId) {
        return repository.findByOperationId(operationId.value()).map(mapper::toDomain);
    }

    @Override
    public Single<PageView<Transaction>> findByProduct(String productId, ProductTransactionFilter filter,
                                                         PageRequest page) {
        Range<Instant> occurredAt = rangeOf(filter.range());
        org.springframework.data.domain.Pageable pageable = pageableOf(page);
        String type = filter.type() == null ? null : filter.type().name();
        String status = filter.status() == null ? null : filter.status().name();

        Flowable<TransactionDocument> items;
        Single<Long> total;
        if (type != null && status != null) {
            items = repository.findByProductIdAndTypeAndStatusAndOccurredAtBetween(productId, type, status,
                    occurredAt, pageable);
            total = repository.countByProductIdAndTypeAndStatusAndOccurredAtBetween(productId, type, status,
                    occurredAt);
        } else if (type != null) {
            items = repository.findByProductIdAndTypeAndStatusNotAndOccurredAtBetween(productId, type,
                    TransactionStatus.DISCARDED.name(), occurredAt, pageable);
            total = repository.countByProductIdAndTypeAndStatusNotAndOccurredAtBetween(productId, type,
                    TransactionStatus.DISCARDED.name(), occurredAt);
        } else if (status != null) {
            items = repository.findByProductIdAndStatusAndOccurredAtBetween(productId, status, occurredAt, pageable);
            total = repository.countByProductIdAndStatusAndOccurredAtBetween(productId, status, occurredAt);
        } else {
            items = repository.findByProductIdAndStatusNotAndOccurredAtBetween(productId,
                    TransactionStatus.DISCARDED.name(), occurredAt, pageable);
            total = repository.countByProductIdAndStatusNotAndOccurredAtBetween(productId,
                    TransactionStatus.DISCARDED.name(), occurredAt);
        }
        return toPage(items, total, page);
    }

    @Override
    public Single<PageView<Transaction>> findAll(TransactionFilter filter, PageRequest page) {
        Range<Instant> occurredAt = rangeOf(filter.range());
        org.springframework.data.domain.Pageable pageable = pageableOf(page);
        String customerId = filter.customerId();
        String type = filter.type() == null ? null : filter.type().name();
        String status = filter.status() == null ? null : filter.status().name();

        Flowable<TransactionDocument> items;
        Single<Long> total;
        if (type != null && status != null) {
            items = repository.findByCustomerIdAndTypeAndStatusAndOccurredAtBetween(customerId, type, status,
                    occurredAt, pageable);
            total = repository.countByCustomerIdAndTypeAndStatusAndOccurredAtBetween(customerId, type, status,
                    occurredAt);
        } else if (type != null) {
            items = repository.findByCustomerIdAndTypeAndStatusNotAndOccurredAtBetween(customerId, type,
                    TransactionStatus.DISCARDED.name(), occurredAt, pageable);
            total = repository.countByCustomerIdAndTypeAndStatusNotAndOccurredAtBetween(customerId, type,
                    TransactionStatus.DISCARDED.name(), occurredAt);
        } else if (status != null) {
            items = repository.findByCustomerIdAndStatusAndOccurredAtBetween(customerId, status, occurredAt,
                    pageable);
            total = repository.countByCustomerIdAndStatusAndOccurredAtBetween(customerId, status, occurredAt);
        } else {
            items = repository.findByCustomerIdAndStatusNotAndOccurredAtBetween(customerId,
                    TransactionStatus.DISCARDED.name(), occurredAt, pageable);
            total = repository.countByCustomerIdAndStatusNotAndOccurredAtBetween(customerId,
                    TransactionStatus.DISCARDED.name(), occurredAt);
        }
        return toPage(items, total, page);
    }

    @Override
    public Flowable<Transaction> findPendingOlderThan(Instant threshold) {
        return repository.findByStatusAndCreatedAtBefore(TransactionStatus.PENDING.name(), threshold)
                .map(mapper::toDomain);
    }

    private Single<PageView<Transaction>> toPage(Flowable<TransactionDocument> items, Single<Long> total,
                                                   PageRequest page) {
        return items.map(mapper::toDomain).toList()
                .zipWith(total, (list, count) -> PageView.of(list, page, count));
    }

    private Range<Instant> rangeOf(DateRange range) {
        Instant from = range == null ? Instant.EPOCH : range.from();
        Instant to = range == null ? OPEN_ENDED_UPPER_BOUND : range.to();
        return Range.from(Range.Bound.inclusive(from)).to(Range.Bound.exclusive(to));
    }

    private org.springframework.data.domain.Pageable pageableOf(PageRequest page) {
        return org.springframework.data.domain.PageRequest.of(page.page(), page.size(),
                Sort.by(Sort.Direction.DESC, "occurredAt"));
    }

    /** data-model.md 3.1: a {@code DuplicateKeyException} on {@code uk_tx_operation_id} is a
     *  genuine race between two identical requests (the use case already checked {@code
     *  findByOperationId} came back empty before building this very save) — re-read what the
     *  winner wrote and hand that back instead of a raw Mongo error, so the loser's request
     *  still gets a correct idempotent result rather than a 500. */
    private Single<Transaction> recoverFromOperationIdRace(OperationId operationId, Throwable error) {
        if (!(error instanceof DuplicateKeyException) || !messageOf(error).contains("uk_tx_operation_id")) {
            return Single.error(error);
        }
        return repository.findByOperationId(operationId.value())
                .map(mapper::toDomain)
                .switchIfEmpty(Single.error(error));
    }

    private String messageOf(Throwable error) {
        return error.getMessage() == null ? "" : error.getMessage();
    }
}
