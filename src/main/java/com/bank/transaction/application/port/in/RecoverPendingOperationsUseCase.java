package com.bank.transaction.application.port.in;

import com.bank.transaction.application.view.RecoveryResult;
import io.reactivex.rxjava3.core.Single;

public interface RecoverPendingOperationsUseCase {

    /** {@code olderThanMinutes} nulo = usa el umbral configurado (Config Server, R6). */
    Single<RecoveryResult> execute(Integer olderThanMinutes);
}
