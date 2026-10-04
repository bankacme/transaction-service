package com.bank.transaction.infrastructure.adapter.out.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * The {@code transactions} collection (data-model.md 3.1): one document per movement.
 * {@code amount}/{@code resultingBalance} are plain {@link BigDecimal} fields — with {@link
 * com.bank.transaction.infrastructure.config.MongoConfig}'s {@code MongoCustomConversions}
 * registered, Spring Data stores/reads them as {@code Decimal128} without a per-field
 * annotation, same approach already proven in {@code account-service}.
 *
 * <p>No {@code @Version} here (unlike {@link TransferDocument}): data-model.md 3.1 doesn't
 * list one for {@code transactions} — a {@code Transaction} is only ever written by the one
 * use case that owns it at a time (never two different requests racing to mutate the SAME
 * transaction id), so {@code uk_tx_operation_id} is the only concurrency guard it needs
 * (§3.1's own note on resolving a race by re-reading and applying the idempotency rule).
 */
@Document("transactions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionDocument {

    @Id
    private String id;

    private String operationId;

    private String productId;

    private String productType;

    private String customerId;

    private String type;

    private BigDecimal amount;

    private BigDecimal resultingBalance;

    private String status;

    private FailureReasonData failureReason;

    private String transferId;

    private String parentTransactionId;

    private String payerCustomerId;

    private String description;

    private ReversalData reversal;

    private Instant occurredAt;

    private Instant createdAt;

    private Instant updatedAt;
}
