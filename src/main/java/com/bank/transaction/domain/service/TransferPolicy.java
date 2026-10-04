package com.bank.transaction.domain.service;

import com.bank.transaction.domain.exception.BusinessRuleViolationException;
import com.bank.transaction.domain.model.AccountSnapshot;
import com.bank.transaction.domain.model.AccountStatus;
import com.bank.transaction.domain.model.Money;
import com.bank.transaction.domain.model.TransferKind;

/**
 * Puro (ficha, sección 3.5): no consulta nada, recibe los {@link AccountSnapshot} de origen y
 * destino ya resueltos por {@code AccountLookupPort} y decide el {@link TransferKind} o lanza
 * el error de negocio correspondiente. Reglas 5 y 6.
 */
public final class TransferPolicy {

    private TransferPolicy() {
    }

    public static TransferKind evaluate(AccountSnapshot source, AccountSnapshot target, Money amount) {
        if (source.accountId().equals(target.accountId())) {
            throw new BusinessRuleViolationException("SAME_ACCOUNT", "Source and target account must be different");
        }
        requireActive(source, "source");
        requireActive(target, "target");
        if (amount.isZero()) {
            throw new BusinessRuleViolationException("INVALID_AMOUNT", "Amount must be greater than zero");
        }
        return source.customerId().equals(target.customerId()) ? TransferKind.OWN : TransferKind.THIRD_PARTY;
    }

    private static void requireActive(AccountSnapshot account, String role) {
        if (account.status() != AccountStatus.ACTIVE) {
            throw new BusinessRuleViolationException("ACCOUNT_INACTIVE",
                    "The " + role + " account " + account.accountId() + " is not active");
        }
    }
}
