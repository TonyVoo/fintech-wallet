package com.fintech.wallet.account.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountTest {

    private Account newAccount() {
        return new Account(UUID.randomUUID(), "123456789012", "Nguyen Van A", "VND");
    }

    @Test
    void depositIncreasesBalance() {
        Account account = newAccount();
        account.deposit(new BigDecimal("100.50"));
        assertThat(account.getBalance()).isEqualByComparingTo("100.50");
    }

    @Test
    void withdrawDecreasesBalance() {
        Account account = newAccount();
        account.deposit(new BigDecimal("100"));
        account.withdraw(new BigDecimal("40"));
        assertThat(account.getBalance()).isEqualByComparingTo("60");
    }

    @Test
    void withdrawMoreThanBalanceFails() {
        Account account = newAccount();
        account.deposit(new BigDecimal("10"));
        assertThatThrownBy(() -> account.withdraw(new BigDecimal("10.01")))
                .isInstanceOf(InsufficientFundsException.class);
        assertThat(account.getBalance()).isEqualByComparingTo("10");
    }

    @Test
    void nonPositiveAmountRejected() {
        Account account = newAccount();
        assertThatThrownBy(() -> account.deposit(BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> account.withdraw(new BigDecimal("-1")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
