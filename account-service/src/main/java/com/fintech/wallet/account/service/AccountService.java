package com.fintech.wallet.account.service;

import com.fintech.wallet.account.domain.Account;
import com.fintech.wallet.account.domain.AccountNotFoundException;
import com.fintech.wallet.account.repository.AccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;

@Service
public class AccountService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AccountRepository repository;
    private final TransactionTemplate tx;
    private final OptimisticLockRetry retry;

    public AccountService(AccountRepository repository, PlatformTransactionManager transactionManager,
                          OptimisticLockRetry retry) {
        this.repository = repository;
        this.tx = new TransactionTemplate(transactionManager);
        this.retry = retry;
    }

    @Transactional
    public Account create(CurrentUser user, String ownerName, String currency) {
        String number;
        do {
            number = generateAccountNumber();
        } while (repository.existsByAccountNumber(number));
        return repository.save(new Account(user.id(), number, ownerName, currency));
    }

    @Transactional(readOnly = true)
    public List<Account> listMine(CurrentUser user) {
        return repository.findByOwnerIdOrderByCreatedAtDesc(user.id());
    }

    @Transactional(readOnly = true)
    public Account get(CurrentUser user, UUID id) {
        return findAccessible(user, id);
    }

    // Deliberately NOT @Transactional: the retry loop must sit outside the transaction,
    // so each attempt gets a fresh transaction and a fresh read of the row.
    public Account deposit(CurrentUser user, UUID id, BigDecimal amount) {
        return retry.execute(() -> tx.execute(status -> {
            Account account = findAccessible(user, id);
            account.deposit(amount);
            return account;
        }));
    }

    public Account withdraw(CurrentUser user, UUID id, BigDecimal amount) {
        return retry.execute(() -> tx.execute(status -> {
            Account account = findAccessible(user, id);
            account.withdraw(amount);
            return account;
        }));
    }

    /**
     * Someone else's account is reported as "not found", not "forbidden",
     * so callers cannot probe which account ids exist.
     */
    private Account findAccessible(CurrentUser user, UUID id) {
        return repository.findById(id)
                .filter(user::canAccess)
                .orElseThrow(() -> new AccountNotFoundException(id));
    }

    private static String generateAccountNumber() {
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            sb.append(RANDOM.nextInt(10));
        }
        return sb.toString();
    }
}
