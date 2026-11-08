package com.fintech.wallet.account.service;

import com.fintech.wallet.account.domain.Account;
import com.fintech.wallet.account.domain.AccountNotFoundException;
import com.fintech.wallet.account.repository.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccountServiceAuthorizationTest {

    private final AccountRepository repository = mock(AccountRepository.class);
    private final AccountService service = new AccountService(
            repository, mock(PlatformTransactionManager.class), new OptimisticLockRetry(1, 0, 0));

    private final CurrentUser owner = new CurrentUser(UUID.randomUUID(), false);
    private final CurrentUser stranger = new CurrentUser(UUID.randomUUID(), false);
    private final CurrentUser admin = new CurrentUser(UUID.randomUUID(), true);

    private Account account;

    @BeforeEach
    void setUp() {
        account = new Account(owner.id(), "123456789012", "Owner", "VND");
        when(repository.findById(account.getId())).thenReturn(Optional.of(account));
    }

    @Test
    void ownerCanReadAndChangeOwnAccount() {
        assertThat(service.get(owner, account.getId())).isSameAs(account);
        service.deposit(owner, account.getId(), new BigDecimal("50"));
        assertThat(account.getBalance()).isEqualByComparingTo("50");
    }

    @Test
    void adminCanAccessAnyAccount() {
        assertThat(service.get(admin, account.getId())).isSameAs(account);
    }

    @Test
    void strangerSeesNotFoundAndBalanceIsUntouched() {
        assertThatThrownBy(() -> service.get(stranger, account.getId()))
                .isInstanceOf(AccountNotFoundException.class);
        assertThatThrownBy(() -> service.deposit(stranger, account.getId(), new BigDecimal("50")))
                .isInstanceOf(AccountNotFoundException.class);
        assertThatThrownBy(() -> service.withdraw(stranger, account.getId(), new BigDecimal("1")))
                .isInstanceOf(AccountNotFoundException.class);
        assertThat(account.getBalance()).isEqualByComparingTo("0");
    }
}
