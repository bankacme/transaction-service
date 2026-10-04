package com.bank.transaction.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.transaction.domain.exception.BusinessRuleViolationException;
import com.bank.transaction.domain.model.AccountSnapshot;
import com.bank.transaction.domain.model.AccountStatus;
import com.bank.transaction.domain.model.AccountType;
import com.bank.transaction.domain.model.Money;
import com.bank.transaction.domain.model.TransferKind;
import java.math.BigDecimal;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class TransferPolicyTest {

    private final Money amount = Money.of(new BigDecimal("100.00"));

    private AccountSnapshot account(String accountId, String customerId, AccountStatus status) {
        return new AccountSnapshot(accountId, customerId, AccountType.SAVINGS, status);
    }

    @Test
    void rejectsTransferringToTheSameAccount() {
        AccountSnapshot account = account("acc-A", "cust-A", AccountStatus.ACTIVE);

        assertThatThrownBy(() -> TransferPolicy.evaluate(account, account, amount))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(e -> assertThat(((BusinessRuleViolationException) e).getErrorCode())
                        .isEqualTo("SAME_ACCOUNT"));
    }

    @Test
    void rejectsAnInactiveSourceAccount() {
        AccountSnapshot source = account("acc-A", "cust-A", AccountStatus.INACTIVE);
        AccountSnapshot target = account("acc-B", "cust-B", AccountStatus.ACTIVE);

        assertThatThrownBy(() -> TransferPolicy.evaluate(source, target, amount))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(e -> assertThat(((BusinessRuleViolationException) e).getErrorCode())
                        .isEqualTo("ACCOUNT_INACTIVE"));
    }

    @Test
    void rejectsAnInactiveTargetAccount() {
        AccountSnapshot source = account("acc-A", "cust-A", AccountStatus.ACTIVE);
        AccountSnapshot target = account("acc-B", "cust-B", AccountStatus.INACTIVE);

        assertThatThrownBy(() -> TransferPolicy.evaluate(source, target, amount))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(e -> assertThat(((BusinessRuleViolationException) e).getErrorCode())
                        .isEqualTo("ACCOUNT_INACTIVE"));
    }

    @Test
    void rejectsAZeroAmount() {
        AccountSnapshot source = account("acc-A", "cust-A", AccountStatus.ACTIVE);
        AccountSnapshot target = account("acc-B", "cust-B", AccountStatus.ACTIVE);

        assertThatThrownBy(() -> TransferPolicy.evaluate(source, target, Money.zero()))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(e -> assertThat(((BusinessRuleViolationException) e).getErrorCode())
                        .isEqualTo("INVALID_AMOUNT"));
    }

    @ParameterizedTest(name = "{0} vs {1} -> {2}")
    @MethodSource("ownVsThirdPartyCases")
    void computesTheTransferKindFromWhetherBothAccountsBelongToTheSameCustomer(
            String sourceCustomerId, String targetCustomerId, TransferKind expectedKind) {
        AccountSnapshot source = account("acc-A", sourceCustomerId, AccountStatus.ACTIVE);
        AccountSnapshot target = account("acc-B", targetCustomerId, AccountStatus.ACTIVE);

        assertThat(TransferPolicy.evaluate(source, target, amount)).isEqualTo(expectedKind);
    }

    private static Stream<Arguments> ownVsThirdPartyCases() {
        return Stream.of(
                Arguments.of("cust-A", "cust-A", TransferKind.OWN),
                Arguments.of("cust-A", "cust-B", TransferKind.THIRD_PARTY));
    }
}
