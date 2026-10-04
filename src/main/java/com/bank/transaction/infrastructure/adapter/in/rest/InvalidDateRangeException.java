package com.bank.transaction.infrastructure.adapter.in.rest;

import java.time.LocalDate;

/**
 * {@code from} after {@code to} on a history query (contract/data-model.md section 6): its own
 * 400 code, {@code INVALID_DATE_RANGE}, distinct from the generic {@code VALIDATION_ERROR}.
 * Public (unlike {@link MovementRejectedException}/{@link TransferRejectedException}): {@code
 * TransactionRestMapper} throws it from a different package.
 */
public class InvalidDateRangeException extends RuntimeException {

    public InvalidDateRangeException(LocalDate from, LocalDate to) {
        super("from (" + from + ") must not be after to (" + to + ")");
    }
}
