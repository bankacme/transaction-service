package com.bank.transaction.infrastructure.mapper;

import com.bank.transaction.application.command.RecordExternalMovementCommand;
import com.bank.transaction.application.command.RegisterMovementCommand;
import com.bank.transaction.application.command.StartTransferCommand;
import com.bank.transaction.application.command.UpdateTransactionDescriptionCommand;
import com.bank.transaction.application.port.in.ProductTransactionFilter;
import com.bank.transaction.application.port.in.TransactionFilter;
import com.bank.transaction.application.port.in.TransferFilter;
import com.bank.transaction.application.view.PageRequest;
import com.bank.transaction.application.view.PageView;
import com.bank.transaction.application.view.RecoveryResult;
import com.bank.transaction.domain.model.DateRange;
import com.bank.transaction.domain.model.FailureReason;
import com.bank.transaction.domain.model.Money;
import com.bank.transaction.domain.model.OperationId;
import com.bank.transaction.domain.model.ProductRef;
import com.bank.transaction.domain.model.ProductType;
import com.bank.transaction.domain.model.ReversalOutcome;
import com.bank.transaction.domain.model.Transaction;
import com.bank.transaction.domain.model.TransactionId;
import com.bank.transaction.domain.model.TransactionReversal;
import com.bank.transaction.domain.model.TransactionType;
import com.bank.transaction.domain.model.Transfer;
import com.bank.transaction.infrastructure.adapter.in.rest.InvalidDateRangeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.stereotype.Component;

/**
 * REST DTOs (generated from the contract) &lt;-&gt; application/domain types. Hand-written, not
 * MapStruct — same deliberate deviation, same reasoning, as the R4 persistence mappers: almost
 * every field needs VO-unwrapping.
 *
 * <p><b>{@code requestedBy} on a transfer</b>: the contract reads it off the token's {@code sub}
 * claim, {@code anonymous} with security disabled (same deferral as {@code AccountController}'s
 * own javadoc in account-service — nothing here checks a token until {@code security.enabled}
 * really exists, P3/{@code auth-service}). {@link #ANONYMOUS_REQUESTER} stands in until then.
 *
 * <p><b>{@code MovementResponse.feeTransaction}</b> is a known, deliberate gap: {@code
 * RegisterDepositUseCase}/{@code RegisterWithdrawalUseCase} (R3) only ever return the PARENT
 * {@link Transaction} — {@code AbstractRegisterMovementUseCase.saveWithFee} saves the {@code
 * FEE} movement alongside it but never surfaces it through the port's return type. Widening that
 * port to return both would touch R3's already-validated-against-real-Mongo tests for no
 * correctness gain (the fee movement IS saved and its own event IS published; only this one
 * optional response field can't be filled from here), so {@link #toMovementResponseDto} always
 * leaves {@code feeTransaction} unset rather than guess at it. Revisit if/when R3's port is
 * widened.
 *
 * <p><b>{@code Reversal.outcome}</b>: the contract's enum is {@code PENDING}/{@code
 * REVERSED}/{@code FAILED}; the domain's {@link ReversalOutcome} (R2) only ever reaches {@code
 * REVERSED} or {@code REVERSAL_FAILED} (mapped to the contract's {@code FAILED} — different
 * name, same meaning). The contract's {@code PENDING} has no domain producer yet in P1/P2 (a
 * {@link Transaction#reversal()} is only ever set already-decided, via {@code markReversed}/
 * {@code markReversalFailed}), so {@link #toDto(TransactionReversal)} never emits it; it's
 * reserved for whenever an asynchronous (P3, Kafka) reversal flow needs an in-flight state.
 */
@Component
public class TransactionRestMapper {

    /** See the class javadoc: stands in for the token's {@code sub} until {@code
     *  security.enabled} exists (P3). */
    static final String ANONYMOUS_REQUESTER = "anonymous";

    // ---- commands (REST request -> application) ----

    public RegisterMovementCommand toCommand(
            com.bank.transaction.infrastructure.adapter.in.rest.dto.MovementRequest dto) {
        return new RegisterMovementCommand(new OperationId(dto.getOperationId()), dto.getAccountId(),
                Money.of(dto.getAmount()), dto.getDescription());
    }

    public StartTransferCommand toCommand(
            com.bank.transaction.infrastructure.adapter.in.rest.dto.StartTransferRequest dto) {
        return new StartTransferCommand(new OperationId(dto.getOperationId()), dto.getSourceAccountId(),
                dto.getTargetAccountId(), Money.of(dto.getAmount()), dto.getDescription(), ANONYMOUS_REQUESTER);
    }

    public UpdateTransactionDescriptionCommand toCommand(String transactionId,
            com.bank.transaction.infrastructure.adapter.in.rest.dto.UpdateDescriptionRequest dto) {
        return new UpdateTransactionDescriptionCommand(new TransactionId(transactionId), dto.getDescription());
    }

    /** Pre-existing gap, not introduced here: the contract requires {@code occurredAt} on this
     *  request, but neither {@link RecordExternalMovementCommand} (R3) nor {@code
     *  Transaction.record} (R2) has a slot for it — that factory always stamps {@code
     *  occurredAt} with {@code Clock.instant()} ("now", when it was registered here), not the
     *  caller-supplied moment it actually happened in {@code credit-service}. So {@code
     *  dto.getOccurredAt()} is read nowhere below; closing this gap means widening R2's
     *  factory, out of scope for R5. */
    public RecordExternalMovementCommand toCommand(
            com.bank.transaction.infrastructure.adapter.in.rest.dto.RecordExternalMovementRequest dto) {
        ProductType productType = ProductType.valueOf(dto.getProductType().name());
        TransactionType type = TransactionType.valueOf(dto.getType().name());
        return new RecordExternalMovementCommand(new OperationId(dto.getOperationId()),
                new ProductRef(dto.getProductId(), productType), dto.getCustomerId(), type, Money.of(dto.getAmount()),
                Money.of(dto.getResultingBalance()), dto.getDescription());
    }

    // ---- filters & paging (REST query params -> application) ----

    public ProductTransactionFilter toProductTransactionFilter(LocalDate from, LocalDate to,
            com.bank.transaction.infrastructure.adapter.in.rest.dto.TransactionType type,
            com.bank.transaction.infrastructure.adapter.in.rest.dto.TransactionStatus status) {
        return new ProductTransactionFilter(toRange(from, to), toDomainType(type), toDomainStatus(status));
    }

    public TransactionFilter toTransactionFilter(String customerId, LocalDate from, LocalDate to,
            com.bank.transaction.infrastructure.adapter.in.rest.dto.TransactionType type,
            com.bank.transaction.infrastructure.adapter.in.rest.dto.TransactionStatus status) {
        return new TransactionFilter(customerId, toDomainType(type), toDomainStatus(status), toRange(from, to));
    }

    public TransferFilter toTransferFilter(String operationId, String accountId,
            com.bank.transaction.infrastructure.adapter.in.rest.dto.TransferStatus status) {
        com.bank.transaction.domain.model.TransferStatus domainStatus = status == null ? null
                : com.bank.transaction.domain.model.TransferStatus.valueOf(status.name());
        return new TransferFilter(accountId, domainStatus, operationId);
    }

    public PageRequest toPageRequest(Integer page, Integer size) {
        return new PageRequest(page == null ? 0 : page, size == null ? 20 : size);
    }

    /** {@code from > to} is its own 400 code ({@code INVALID_DATE_RANGE}, contract/data-model.md
     *  section 6) — distinct from the generic {@code VALIDATION_ERROR} — so it's checked here,
     *  before ever reaching {@link DateRange}'s own (generic-{@code IllegalArgumentException})
     *  invariant, and signaled with {@link InvalidDateRangeException} so {@code
     *  GlobalExceptionHandler} can give it the right code. */
    private DateRange toRange(LocalDate from, LocalDate to) {
        if (from == null && to == null) {
            return null;
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidDateRangeException(from, to);
        }
        Instant fromInstant = from == null ? Instant.EPOCH : from.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant toInstant = to == null ? Instant.parse("9999-12-31T23:59:59.999999999Z")
                : to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return new DateRange(fromInstant, toInstant);
    }

    private TransactionType toDomainType(com.bank.transaction.infrastructure.adapter.in.rest.dto.TransactionType type) {
        return type == null ? null : TransactionType.valueOf(type.name());
    }

    private com.bank.transaction.domain.model.TransactionStatus toDomainStatus(
            com.bank.transaction.infrastructure.adapter.in.rest.dto.TransactionStatus status) {
        return status == null ? null : com.bank.transaction.domain.model.TransactionStatus.valueOf(status.name());
    }

    // ---- responses (domain -> REST DTO) ----

    public com.bank.transaction.infrastructure.adapter.in.rest.dto.Transaction toDto(Transaction tx) {
        com.bank.transaction.infrastructure.adapter.in.rest.dto.Transaction dto =
                new com.bank.transaction.infrastructure.adapter.in.rest.dto.Transaction();
        dto.setId(tx.id().value());
        dto.setOperationId(tx.operationId().value());
        dto.setProductId(tx.product().productId());
        dto.setProductType(
                com.bank.transaction.infrastructure.adapter.in.rest.dto.ProductType.valueOf(tx.product().productType()
                        .name()));
        dto.setCustomerId(tx.customerId());
        dto.setType(com.bank.transaction.infrastructure.adapter.in.rest.dto.TransactionType.valueOf(tx.type().name()));
        dto.setAmount(tx.amount().amount());
        if (tx.resultingBalance() != null) {
            dto.setResultingBalance(tx.resultingBalance().amount());
        }
        dto.setStatus(
                com.bank.transaction.infrastructure.adapter.in.rest.dto.TransactionStatus.valueOf(tx.status().name()));
        if (tx.failureReason() != null) {
            dto.setFailureReason(toDto(tx.failureReason()));
        }
        if (tx.transferId() != null) {
            dto.setTransferId(tx.transferId().value());
        }
        if (tx.parentTransactionId() != null) {
            dto.setParentTransactionId(tx.parentTransactionId().value());
        }
        dto.setPayerCustomerId(tx.payerCustomerId());
        dto.setDescription(tx.description());
        if (tx.reversal() != null) {
            dto.setReversal(toDto(tx.reversal()));
        }
        dto.setOccurredAt(toOffsetDateTime(tx.occurredAt()));
        dto.setCreatedAt(toOffsetDateTime(tx.createdAt()));
        dto.setUpdatedAt(toOffsetDateTime(tx.updatedAt()));
        return dto;
    }

    public com.bank.transaction.infrastructure.adapter.in.rest.dto.Transfer toDto(Transfer transfer) {
        com.bank.transaction.infrastructure.adapter.in.rest.dto.Transfer dto =
                new com.bank.transaction.infrastructure.adapter.in.rest.dto.Transfer();
        dto.setId(transfer.id().value());
        dto.setOperationId(transfer.operationId().value());
        dto.setSourceAccountId(transfer.sourceAccountId());
        dto.setTargetAccountId(transfer.targetAccountId());
        dto.setAmount(transfer.amount().amount());
        dto.setKind(
                com.bank.transaction.infrastructure.adapter.in.rest.dto.TransferKind.valueOf(transfer.kind().name()));
        dto.setStatus(
                com.bank.transaction.infrastructure.adapter.in.rest.dto.TransferStatus.valueOf(transfer.status()
                        .name()));
        if (transfer.failureReason() != null) {
            dto.setFailureReason(toDto(transfer.failureReason()));
        }
        if (transfer.debitTransactionId() != null) {
            dto.setDebitTransactionId(transfer.debitTransactionId().value());
        }
        if (transfer.creditTransactionId() != null) {
            dto.setCreditTransactionId(transfer.creditTransactionId().value());
        }
        dto.setCompensationAttempts(transfer.compensationAttempts());
        dto.setDescription(transfer.description());
        dto.setRequestedBy(transfer.requestedBy());
        dto.setCreatedAt(toOffsetDateTime(transfer.createdAt()));
        dto.setUpdatedAt(toOffsetDateTime(transfer.updatedAt()));
        return dto;
    }

    /** See the class javadoc: {@code feeTransaction} is never filled from here (R3's register
     *  use cases don't surface it). */
    public com.bank.transaction.infrastructure.adapter.in.rest.dto.MovementResponse toMovementResponseDto(
            Transaction parent) {
        com.bank.transaction.infrastructure.adapter.in.rest.dto.MovementResponse dto =
                new com.bank.transaction.infrastructure.adapter.in.rest.dto.MovementResponse();
        dto.setTransaction(toDto(parent));
        return dto;
    }

    public com.bank.transaction.infrastructure.adapter.in.rest.dto.TransactionPage toDto(PageView<Transaction> page) {
        com.bank.transaction.infrastructure.adapter.in.rest.dto.TransactionPage dto =
                new com.bank.transaction.infrastructure.adapter.in.rest.dto.TransactionPage();
        dto.setPage(page.page());
        dto.setSize(page.size());
        dto.setTotalElements(page.totalElements());
        dto.setTotalPages(totalPages(page));
        dto.setContent(page.items().stream().map(this::toDto).toList());
        return dto;
    }

    /** {@code PageMeta.totalPages} isn't carried by {@link PageView} (R3): derived here the
     *  usual way, {@code size} is always &gt;= 1 ({@link PageRequest}'s own invariant). */
    private int totalPages(PageView<Transaction> page) {
        return (int) Math.ceil((double) page.totalElements() / page.size());
    }

    /** {@code olderThanMinutes} and {@code ranAt} aren't part of the application-layer {@link
     *  RecoveryResult} (R3) — {@code olderThanMinutes} is echoed back from the request (the
     *  actual configured default isn't wired until R6's Config Server properties exist, so
     *  absent-from-the-request shows as 0 here rather than guess at a default); {@code ranAt}
     *  is simply "now" at response-building time, which is close enough for a synchronous run.
     *  {@code stillInProgress} combines both universes into the one count the contract asks
     *  for. */
    public com.bank.transaction.infrastructure.adapter.in.rest.dto.RecoveryResult toDto(RecoveryResult result,
            Integer requestedOlderThanMinutes, Instant ranAt) {
        com.bank.transaction.infrastructure.adapter.in.rest.dto.RecoveryResult dto =
                new com.bank.transaction.infrastructure.adapter.in.rest.dto.RecoveryResult();
        dto.setRanAt(toOffsetDateTime(ranAt));
        dto.setOlderThanMinutes(requestedOlderThanMinutes == null ? 0 : requestedOlderThanMinutes);
        dto.setPendingTransactionsFound(result.transactionsRetried());
        dto.setTransactionsResolved(result.transactionsCompleted() + result.transactionsFailed());
        dto.setTransfersFound(result.transfersRetried());
        dto.setTransfersResolved(result.transfersCompleted() + result.transfersFailed());
        int transactionsStillInProgress =
                result.transactionsRetried() - result.transactionsCompleted() - result.transactionsFailed();
        int transfersStillInProgress =
                result.transfersRetried() - result.transfersCompleted() - result.transfersFailed();
        dto.setStillInProgress(transactionsStillInProgress + transfersStillInProgress);
        return dto;
    }

    private com.bank.transaction.infrastructure.adapter.in.rest.dto.FailureReason toDto(FailureReason reason) {
        com.bank.transaction.infrastructure.adapter.in.rest.dto.FailureReason dto =
                new com.bank.transaction.infrastructure.adapter.in.rest.dto.FailureReason();
        dto.setCode(reason.code());
        dto.setMessage(reason.message());
        return dto;
    }

    private com.bank.transaction.infrastructure.adapter.in.rest.dto.Reversal toDto(TransactionReversal reversal) {
        com.bank.transaction.infrastructure.adapter.in.rest.dto.Reversal dto =
                new com.bank.transaction.infrastructure.adapter.in.rest.dto.Reversal();
        dto.setOperationId(reversal.operationId().value());
        dto.setOutcome(reversal.outcome() == ReversalOutcome.REVERSED
                ? com.bank.transaction.infrastructure.adapter.in.rest.dto.Reversal.OutcomeEnum.REVERSED
                : com.bank.transaction.infrastructure.adapter.in.rest.dto.Reversal.OutcomeEnum.FAILED);
        dto.setReasonCode(reversal.reasonCode());
        return dto;
    }

    private OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    /** Causal-ordering trick shared by every idempotent-create endpoint ({@code POST /deposits},
     *  {@code /withdrawals}, {@code /transfers}, {@code /transactions/records}): the contract
     *  wants 201/200 to depend on whether THIS call created the row or merely replayed an
     *  already-existing one, but the R3 use cases only ever return the resulting aggregate, with
     *  no separate "was this new" flag (the replay branch returns the found row completely
     *  unchanged, see e.g. {@code AbstractRegisterMovementUseCase.replay}). A brand-new row's
     *  {@code createdAt} is always set (via {@code Clock.instant()}) AFTER the controller
     *  captured {@code requestReceivedAt}, right before calling the use case; a replayed row's
     *  {@code createdAt} is always from a strictly earlier, already-finished request. So {@code
     *  !createdAt.isBefore(requestReceivedAt)} is a reliable "was this call the one that created
     *  it" signal, with no change needed to R3's already-validated-against-real-Mongo
     *  interfaces. */
    public boolean wasCreatedByThisRequest(Instant entityCreatedAt, Instant requestReceivedAt) {
        return !entityCreatedAt.isBefore(requestReceivedAt);
    }
}
