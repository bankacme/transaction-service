package com.bank.transaction.application.port.out;

import com.bank.transaction.domain.model.AccountSnapshot;
import io.reactivex.rxjava3.core.Maybe;

public interface AccountLookupPort {

    /** Vacío significa que la cuenta no existe. REST + circuit breaker en P1/P2; read model
     *  en P3 (ficha 4.2). */
    Maybe<AccountSnapshot> findById(String accountId);
}
