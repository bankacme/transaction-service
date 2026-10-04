package com.bank.transaction.application.usecase;

import com.bank.transaction.application.port.in.TransferFilter;
import com.bank.transaction.application.port.out.TransferRepositoryPort;
import com.bank.transaction.domain.model.OperationId;
import com.bank.transaction.domain.model.Transfer;
import com.bank.transaction.domain.model.TransferId;
import com.bank.transaction.domain.model.TransferStatus;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** In-memory stand-in for the Mongo adapter (R4), reusable across use-case tests. */
public class InMemoryTransferRepository implements TransferRepositoryPort {

    private static final Set<TransferStatus> IN_PROGRESS = Set.of(TransferStatus.STARTED,
            TransferStatus.SOURCE_DEBITED, TransferStatus.COMPENSATING);

    private final Map<String, Transfer> byId = new LinkedHashMap<>();

    @Override
    public Single<Transfer> save(Transfer transfer) {
        byId.put(transfer.id().value(), transfer);
        return Single.just(transfer);
    }

    @Override
    public Maybe<Transfer> findById(TransferId id) {
        Transfer found = byId.get(id.value());
        return found == null ? Maybe.empty() : Maybe.just(found);
    }

    @Override
    public Maybe<Transfer> findByOperationId(OperationId operationId) {
        return byId.values().stream()
                .filter(transfer -> transfer.operationId().equals(operationId))
                .findFirst()
                .map(Maybe::just)
                .orElseGet(Maybe::empty);
    }

    @Override
    public Flowable<Transfer> findAll(TransferFilter filter) {
        List<Transfer> matches = byId.values().stream()
                .filter(transfer -> filter.accountId() == null || filter.accountId().equals(transfer.sourceAccountId())
                        || filter.accountId().equals(transfer.targetAccountId()))
                .filter(transfer -> filter.status() == null || filter.status() == transfer.status())
                .filter(transfer -> filter.operationId() == null
                        || filter.operationId().equals(transfer.operationId().value()))
                .toList();
        return Flowable.fromIterable(matches);
    }

    @Override
    public Flowable<Transfer> findInProgressOlderThan(Instant threshold) {
        return Flowable.fromIterable(byId.values().stream()
                .filter(transfer -> IN_PROGRESS.contains(transfer.status()))
                .filter(transfer -> transfer.updatedAt().isBefore(threshold))
                .toList());
    }

    public int size() {
        return byId.size();
    }
}
