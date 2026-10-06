package com.bank.transaction.application.usecase;

import com.bank.transaction.application.port.out.TransactionRepositoryPort;
import com.bank.transaction.application.port.out.UnitOfWorkPort;
import com.bank.transaction.domain.model.MovementOutcome;
import com.bank.transaction.domain.model.ReversalOutcome;
import com.bank.transaction.domain.model.Transaction;
import com.bank.transaction.domain.model.TransactionReversal;
import com.bank.transaction.domain.model.TransactionStatus;
import com.bank.transaction.domain.model.Transfer;
import io.reactivex.rxjava3.core.Single;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * Lo que la saga ({@code StartTransferUseCaseImpl}) y la recuperación
 * ({@code RecoverPendingOperationsUseCaseImpl}) hacen igual con las patas de una transferencia
 * (data-model 2.4):
 * <ul>
 *   <li>3a / 5a: una pata aplicada se guarda junto con su comisión {@code <op>-OUT-FEE} /
 *       {@code <op>-IN-FEE} (si la cuenta cobró una) y el nuevo estado de la Transfer.</li>
 *   <li>7a: al compensar, la pata de salida pasa a REVERSED y su comisión también (la cuenta
 *       devolvió monto y comisión), junto con la Transfer COMPENSATED.</li>
 * </ul>
 */
final class TransferLegs {

    private final TransactionRepositoryPort transactionRepositoryPort;
    private final UnitOfWorkPort unitOfWorkPort;
    private final Clock clock;

    TransferLegs(TransactionRepositoryPort transactionRepositoryPort, UnitOfWorkPort unitOfWorkPort, Clock clock) {
        this.transactionRepositoryPort = transactionRepositoryPort;
        this.unitOfWorkPort = unitOfWorkPort;
        this.clock = clock;
    }

    /** La Transfer en su nuevo estado, la pata ya COMPLETED y, si hubo comisión, su FEE. */
    Single<Transfer> saveApplied(Transfer transfer, Transaction completedLeg, MovementOutcome outcome) {
        List<Transaction> writes = new ArrayList<>(List.of(completedLeg));
        if (outcome != null && outcome.fee() != null && !outcome.fee().isZero()) {
            writes.add(Transaction.feeOf(completedLeg, outcome.fee(), outcome.resultingBalance(),
                    "Comision por " + completedLeg.type(), clock));
        }
        return unitOfWorkPort.saveTransferAndTransactions(transfer, writes);
    }

    /** Transfer COMPENSATED + pata de salida REVERSED + su comisión REVERSED (si la tuvo). */
    Single<Transfer> saveCompensated(Transfer compensated, Transaction debitCompleted) {
        TransactionReversal reversal = new TransactionReversal(debitCompleted.operationId().forReversal(),
                ReversalOutcome.REVERSED, null);
        Transaction reversedDebit = debitCompleted.markReversed(reversal, clock);
        return transactionRepositoryPort.findByOperationId(debitCompleted.operationId().forFee())
                .filter(fee -> fee.status() == TransactionStatus.COMPLETED)
                .map(fee -> List.of(reversedDebit, fee.markReversed(reversal, clock)))
                .defaultIfEmpty(List.of(reversedDebit))
                .flatMap(writes -> unitOfWorkPort.saveTransferAndTransactions(compensated, writes));
    }
}
