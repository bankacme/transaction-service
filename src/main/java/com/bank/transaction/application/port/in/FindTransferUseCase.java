package com.bank.transaction.application.port.in;

import com.bank.transaction.domain.model.Transfer;
import com.bank.transaction.domain.model.TransferId;
import io.reactivex.rxjava3.core.Single;

public interface FindTransferUseCase {

    Single<Transfer> execute(TransferId id);
}
