package com.bank.transaction.infrastructure.adapter.out.persistence;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Embebido en {@link TransactionDocument} y {@link TransferDocument} (data-model.md 3.1/3.2:
 *  {@code failureReason.code} / {@code .message}). Shared since {@code FailureReason} is the
 *  same domain VO on both aggregates. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FailureReasonData {

    private String code;

    private String message;
}
