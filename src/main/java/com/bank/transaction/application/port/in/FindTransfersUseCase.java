package com.bank.transaction.application.port.in;

import com.bank.transaction.domain.model.Transfer;
import io.reactivex.rxjava3.core.Flowable;

public interface FindTransfersUseCase {

    Flowable<Transfer> execute(TransferFilter filter);
}
