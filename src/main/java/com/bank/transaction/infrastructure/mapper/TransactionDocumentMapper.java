package com.bank.transaction.infrastructure.mapper;

import com.bank.transaction.domain.model.FailureReason;
import com.bank.transaction.domain.model.Money;
import com.bank.transaction.domain.model.OperationId;
import com.bank.transaction.domain.model.ProductRef;
import com.bank.transaction.domain.model.ProductType;
import com.bank.transaction.domain.model.ReversalOutcome;
import com.bank.transaction.domain.model.Transaction;
import com.bank.transaction.domain.model.TransactionId;
import com.bank.transaction.domain.model.TransactionReversal;
import com.bank.transaction.domain.model.TransactionStatus;
import com.bank.transaction.domain.model.TransactionType;
import com.bank.transaction.domain.model.TransferId;
import com.bank.transaction.infrastructure.adapter.out.persistence.FailureReasonData;
import com.bank.transaction.infrastructure.adapter.out.persistence.ReversalData;
import com.bank.transaction.infrastructure.adapter.out.persistence.TransactionDocument;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * Hand-written, not MapStruct — same reasoning already documented in {@code account-service}'s
 * own {@code AccountDocumentMapper}: almost every field needs VO-unwrapping ({@code
 * Transaction.amount().amount()} vs {@code TransactionDocument.amount}, {@code
 * Transaction.product().productId()} vs the flattened {@code productId}/{@code productType}
 * pair, ...), which MapStruct's automatic property matching would not meaningfully shortcut —
 * it would only hide the exact spots most likely to have a wrong mapping. The ficha (data-model
 * .md section 5) proposes MapStruct; this is a deliberate, documented deviation from it,
 * mirroring the one {@code account-service} already made for the same reason.
 */
@Component
public class TransactionDocumentMapper {

    public TransactionDocument toDocument(Transaction transaction) {
        return TransactionDocument.builder()
                .id(transaction.id().value())
                .operationId(transaction.operationId().value())
                .productId(transaction.product().productId())
                .productType(transaction.product().productType().name())
                .customerId(transaction.customerId())
                .type(transaction.type().name())
                .amount(transaction.amount().amount())
                .resultingBalance(amountOf(transaction.resultingBalance()))
                .status(transaction.status().name())
                .failureReason(failureReasonDataOf(transaction.failureReason()))
                .transferId(transaction.transferId() == null ? null : transaction.transferId().value())
                .parentTransactionId(transaction.parentTransactionId() == null ? null
                        : transaction.parentTransactionId().value())
                .payerCustomerId(transaction.payerCustomerId())
                .description(transaction.description())
                .reversal(reversalDataOf(transaction.reversal()))
                .occurredAt(transaction.occurredAt())
                .createdAt(transaction.createdAt())
                .updatedAt(transaction.updatedAt())
                .build();
    }

    public Transaction toDomain(TransactionDocument document) {
        return new Transaction(
                new TransactionId(document.getId()),
                new OperationId(document.getOperationId()),
                new ProductRef(document.getProductId(), ProductType.valueOf(document.getProductType())),
                document.getCustomerId(),
                TransactionType.valueOf(document.getType()),
                Money.of(document.getAmount()),
                moneyOf(document.getResultingBalance()),
                TransactionStatus.valueOf(document.getStatus()),
                failureReasonOf(document.getFailureReason()),
                document.getTransferId() == null ? null : new TransferId(document.getTransferId()),
                document.getParentTransactionId() == null ? null : new TransactionId(document.getParentTransactionId()),
                document.getPayerCustomerId(),
                document.getDescription(),
                reversalOf(document.getReversal()),
                document.getOccurredAt(),
                document.getCreatedAt(),
                document.getUpdatedAt());
    }

    private BigDecimal amountOf(Money money) {
        return money == null ? null : money.amount();
    }

    private FailureReasonData failureReasonDataOf(FailureReason reason) {
        return reason == null ? null
                : FailureReasonData.builder().code(reason.code()).message(reason.message()).build();
    }

    private FailureReason failureReasonOf(FailureReasonData data) {
        return data == null ? null : new FailureReason(data.getCode(), data.getMessage());
    }

    private ReversalData reversalDataOf(TransactionReversal reversal) {
        return reversal == null ? null
                : ReversalData.builder()
                        .operationId(reversal.operationId().value())
                        .outcome(reversal.outcome().name())
                        .reasonCode(reversal.reasonCode())
                        .build();
    }

    private TransactionReversal reversalOf(ReversalData data) {
        return data == null ? null
                : new TransactionReversal(new OperationId(data.getOperationId()),
                        ReversalOutcome.valueOf(data.getOutcome()), data.getReasonCode());
    }

    private Money moneyOf(BigDecimal amount) {
        return amount == null ? null : Money.of(amount);
    }
}
