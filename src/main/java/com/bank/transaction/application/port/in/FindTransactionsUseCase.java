package com.bank.transaction.application.port.in;

import com.bank.transaction.application.view.PageRequest;
import com.bank.transaction.application.view.PageView;
import com.bank.transaction.domain.model.Transaction;
import io.reactivex.rxjava3.core.Single;

public interface FindTransactionsUseCase {

    /** {@link TransactionFilter#customerId()} es obligatorio (contrato, ficha sección 5). */
    Single<PageView<Transaction>> execute(TransactionFilter filter, PageRequest page);
}
