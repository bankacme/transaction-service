package com.bank.transaction.infrastructure.adapter.out.persistence;

import com.bank.transaction.application.port.in.TransferFilter;
import com.bank.transaction.application.port.out.TransferRepositoryPort;
import com.bank.transaction.domain.model.OperationId;
import com.bank.transaction.domain.model.Transfer;
import com.bank.transaction.domain.model.TransferId;
import com.bank.transaction.domain.model.TransferStatus;
import com.bank.transaction.infrastructure.mapper.TransferDocumentMapper;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * {@code GET /transfers} is unpaginated (capped at 200, data-model.md section 4), so unlike
 * {@link TransactionPersistenceAdapter} this picks ONE broad {@link TransferMongoRepository}
 * query by priority — {@code operationId} first (already a unique lookup), then {@code
 * accountId} (source OR target), then {@code status}, else everything — purely as an
 * optimization to let the DB do as much filtering as it can in one round trip. It does NOT
 * shortcut the rest: {@link #findAll} re-applies ALL THREE {@link TransferFilter} fields in
 * memory afterward as a plain AND, including whichever one the broad query already used, so a
 * caller that passes contradictory filters together (an {@code operationId} alongside an
 * {@code accountId} that transfer doesn't actually have, say) gets an empty result, not the
 * {@code operationId} match with the rest silently ignored. Applying the broad query's own
 * filter field again in memory is redundant but harmless — correctness here comes from the
 * in-memory pass alone; the broad query is free to be as loose as it wants. Safe to filter in
 * memory at all specifically because there's no skip/limit at the database level for it to
 * throw off, unlike the paginated transaction history (see that adapter's own javadoc for why
 * it does NOT take this shortcut).
 */
@Component
public class TransferPersistenceAdapter implements TransferRepositoryPort {

    private static final int MAX_RESULTS = 200;

    private final TransferMongoRepository repository;
    private final TransferDocumentMapper mapper;

    public TransferPersistenceAdapter(TransferMongoRepository repository, TransferDocumentMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Override
    public Single<Transfer> save(Transfer transfer) {
        return repository.save(mapper.toDocument(transfer))
                .map(mapper::toDomain)
                .onErrorResumeNext(error -> recoverFromOperationIdRace(transfer.operationId(), error));
    }

    @Override
    public Maybe<Transfer> findById(TransferId id) {
        return repository.findById(id.value()).map(mapper::toDomain);
    }

    @Override
    public Maybe<Transfer> findByOperationId(OperationId operationId) {
        return repository.findByOperationId(operationId.value()).map(mapper::toDomain);
    }

    @Override
    public Flowable<Transfer> findAll(TransferFilter filter) {
        return broadQuery(filter)
                .map(mapper::toDomain)
                .filter(transfer -> filter.accountId() == null || filter.accountId().equals(transfer.sourceAccountId())
                        || filter.accountId().equals(transfer.targetAccountId()))
                .filter(transfer -> filter.status() == null || filter.status() == transfer.status())
                .filter(transfer -> filter.operationId() == null
                        || filter.operationId().equals(transfer.operationId().value()))
                .take(MAX_RESULTS);
    }

    @Override
    public Flowable<Transfer> findInProgressOlderThan(Instant threshold) {
        List<String> inProgress = List.of(TransferStatus.STARTED.name(), TransferStatus.SOURCE_DEBITED.name(),
                TransferStatus.COMPENSATING.name());
        return repository.findByStatusInAndUpdatedAtBefore(inProgress, threshold).map(mapper::toDomain);
    }

    private Flowable<TransferDocument> broadQuery(TransferFilter filter) {
        if (filter.operationId() != null) {
            return repository.findByOperationId(filter.operationId()).toFlowable();
        }
        if (filter.accountId() != null) {
            return repository.findBySourceAccountIdOrTargetAccountId(filter.accountId(), filter.accountId());
        }
        if (filter.status() != null) {
            return repository.findByStatus(filter.status().name());
        }
        return repository.findAll();
    }

    private Single<Transfer> recoverFromOperationIdRace(OperationId operationId, Throwable error) {
        if (!(error instanceof DuplicateKeyException) || !messageOf(error).contains("uk_transfer_operation_id")) {
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
