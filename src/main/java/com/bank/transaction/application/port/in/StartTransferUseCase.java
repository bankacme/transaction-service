package com.bank.transaction.application.port.in;

import com.bank.transaction.application.command.StartTransferCommand;
import com.bank.transaction.domain.model.Transfer;
import io.reactivex.rxjava3.core.Single;

public interface StartTransferUseCase {

    Single<Transfer> execute(StartTransferCommand command);
}
