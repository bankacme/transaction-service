package com.bank.transaction.infrastructure.adapter.out.persistence;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Embebido en {@link TransactionDocument} (data-model.md 3.1: {@code reversal.operationId} /
 *  {@code .outcome} / {@code .reasonCode}). Only present once a reversal was attempted on the
 *  movement (R2's {@code TransactionReversal} — {@code REVERSED}/{@code REVERSAL_FAILED} only;
 *  the richer {@code PENDING} outcome data-model.md 1.3 describes belongs to the P3
 *  Yanki-initiated reversal-request flow, which is out of scope here same as
 *  {@code HandleMovementResultUseCase}/{@code RecordRequestedMovementUseCase} already are). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReversalData {

    private String operationId;

    private String outcome;

    private String reasonCode;
}
