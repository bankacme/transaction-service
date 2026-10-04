package com.bank.transaction.infrastructure.mapper;

import com.bank.transaction.domain.model.FailureReason;
import com.bank.transaction.domain.model.Money;
import com.bank.transaction.domain.model.OperationId;
import com.bank.transaction.domain.model.TransactionId;
import com.bank.transaction.domain.model.Transfer;
import com.bank.transaction.domain.model.TransferId;
import com.bank.transaction.domain.model.TransferKind;
import com.bank.transaction.domain.model.TransferStatus;
import com.bank.transaction.infrastructure.adapter.out.persistence.FailureReasonData;
import com.bank.transaction.infrastructure.adapter.out.persistence.TransferDocument;
import org.springframework.stereotype.Component;

/** Hand-written, not MapStruct — same reasoning as {@link TransactionDocumentMapper}. */
@Component
public class TransferDocumentMapper {

    public TransferDocument toDocument(Transfer transfer) {
        return TransferDocument.builder()
                .id(transfer.id().value())
                .operationId(transfer.operationId().value())
                .sourceAccountId(transfer.sourceAccountId())
                .targetAccountId(transfer.targetAccountId())
                .sourceCustomerId(transfer.sourceCustomerId())
                .targetCustomerId(transfer.targetCustomerId())
                .amount(transfer.amount().amount())
                .kind(transfer.kind().name())
                .status(transfer.status().name())
                .failureReason(failureReasonDataOf(transfer.failureReason()))
                .debitTransactionId(transfer.debitTransactionId() == null ? null
                        : transfer.debitTransactionId().value())
                .creditTransactionId(transfer.creditTransactionId() == null ? null
                        : transfer.creditTransactionId().value())
                .compensationAttempts(transfer.compensationAttempts())
                .description(transfer.description())
                .requestedBy(transfer.requestedBy())
                .version(transfer.version())
                .createdAt(transfer.createdAt())
                .updatedAt(transfer.updatedAt())
                .build();
    }

    public Transfer toDomain(TransferDocument document) {
        return new Transfer(
                new TransferId(document.getId()),
                new OperationId(document.getOperationId()),
                document.getSourceAccountId(),
                document.getTargetAccountId(),
                Money.of(document.getAmount()),
                TransferKind.valueOf(document.getKind()),
                TransferStatus.valueOf(document.getStatus()),
                failureReasonOf(document.getFailureReason()),
                document.getDebitTransactionId() == null ? null : new TransactionId(document.getDebitTransactionId()),
                document.getCreditTransactionId() == null ? null
                        : new TransactionId(document.getCreditTransactionId()),
                document.getCompensationAttempts(),
                document.getDescription(),
                document.getSourceCustomerId(),
                document.getTargetCustomerId(),
                document.getRequestedBy(),
                document.getVersion(),
                document.getCreatedAt(),
                document.getUpdatedAt());
    }

    private FailureReasonData failureReasonDataOf(FailureReason reason) {
        return reason == null ? null
                : FailureReasonData.builder().code(reason.code()).message(reason.message()).build();
    }

    private FailureReason failureReasonOf(FailureReasonData data) {
        return data == null ? null : new FailureReason(data.getCode(), data.getMessage());
    }
}
