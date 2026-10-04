package com.bank.transaction.application.command;

import com.bank.transaction.domain.model.Money;
import com.bank.transaction.domain.model.OperationId;

/** Comparte forma con {@code DepositRequest} y {@code WithdrawalRequest} (contrato) — lo
 *  que distingue un depósito de un retiro es cuál caso de uso recibe este command
 *  (RegisterDepositUseCase vs RegisterWithdrawalUseCase), no un campo propio. */
public record RegisterMovementCommand(OperationId operationId, String accountId, Money amount, String description) {

    public RegisterMovementCommand {
        if (operationId == null) {
            throw new IllegalArgumentException("operationId is required");
        }
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("accountId must not be blank");
        }
        if (amount == null) {
            throw new IllegalArgumentException("amount is required");
        }
    }
}
