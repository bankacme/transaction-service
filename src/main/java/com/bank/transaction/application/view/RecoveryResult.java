package com.bank.transaction.application.view;

/** Resultado de RecoverPendingOperationsUseCase: cuántos movimientos y transferencias se
 *  reintentaron y en qué terminaron. */
public record RecoveryResult(int transactionsRetried, int transactionsCompleted, int transactionsFailed,
                              int transfersRetried, int transfersCompleted, int transfersFailed) {

    public static RecoveryResult empty() {
        return new RecoveryResult(0, 0, 0, 0, 0, 0);
    }
}
