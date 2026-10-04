package com.bank.transaction.infrastructure.adapter.out.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * The {@code transfers} collection (data-model.md 3.2): one document per transfer, the saga's
 * own state machine. {@code @Version} backs the optimistic concurrency data-model.md 1.2/3.4
 * calls for ("una transición concurrente duplicada se ignora") — two concurrent writers
 * racing to advance the SAME {@code Transfer} (the live request and a recovery pass both
 * touching it, say) have the loser rejected by Spring Data with {@code
 * OptimisticLockingFailureException} rather than silently overwriting the winner's write.
 */
@Document("transfers")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransferDocument {

    @Id
    private String id;

    private String operationId;

    private String sourceAccountId;

    private String targetAccountId;

    private String sourceCustomerId;

    private String targetCustomerId;

    private BigDecimal amount;

    private String kind;

    private String status;

    private FailureReasonData failureReason;

    private String debitTransactionId;

    private String creditTransactionId;

    private int compensationAttempts;

    private String description;

    private String requestedBy;

    @Version
    private long version;

    private Instant createdAt;

    private Instant updatedAt;
}
