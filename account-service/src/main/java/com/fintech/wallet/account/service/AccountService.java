package com.fintech.wallet.account.service;

import com.fintech.wallet.account.domain.Account;
import com.fintech.wallet.account.domain.AccountNotFoundException;
import com.fintech.wallet.account.domain.AccountOperation;
import com.fintech.wallet.account.domain.OperationConflictException;
import com.fintech.wallet.account.domain.OperationType;
import com.fintech.wallet.account.repository.AccountOperationRepository;
import com.fintech.wallet.account.repository.AccountRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class AccountService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AccountRepository repository;
    private final AccountOperationRepository operations;
    private final TransactionTemplate tx;
    private final OptimisticLockRetry retry;

    public AccountService(AccountRepository repository, AccountOperationRepository operations,
                          PlatformTransactionManager transactionManager, OptimisticLockRetry retry) {
        this.repository = repository;
        this.operations = operations;
        this.tx = new TransactionTemplate(transactionManager);
        this.retry = retry;
    }

    // ------------------------------------------------------------------ service-to-service API

    /** Account lookup for trusted services: no ownership filter, the caller does its own checks. */
    @Transactional(readOnly = true)
    public Account getForService(UUID id) {
        return repository.findById(id).orElseThrow(() -> new AccountNotFoundException(id));
    }

    /**
     * Applies a debit or credit exactly once per {@code operationId}.
     *
     * <p>Calling it again with the same id and the same payload is safe and returns ALREADY_APPLIED,
     * which is what lets a caller simply retry after a timeout without knowing whether the first
     * call arrived. The same id with a different payload is a bug in the caller and is rejected.
     */
    public OperationOutcome applyOperation(String operationId, UUID accountId, OperationType type, BigDecimal amount) {
        try {
            return retry.execute(() -> tx.execute(status -> doApply(operationId, accountId, type, amount)));
        } catch (DataIntegrityViolationException e) {
            // an identical request committed between our check and our insert: the primary key stopped
            // the duplicate (and rolled back its balance change), so what is left is to report the replay
            return retry.execute(() -> tx.execute(status -> doApply(operationId, accountId, type, amount)));
        }
    }

    private OperationOutcome doApply(String operationId, UUID accountId, OperationType type, BigDecimal amount) {
        Account account = repository.findById(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));

        Optional<AccountOperation> prior = operations.findById(operationId);
        if (prior.isPresent()) {
            if (!prior.get().matches(accountId, type, amount)) {
                throw new OperationConflictException(operationId);
            }
            return new OperationOutcome(OperationOutcome.Status.ALREADY_APPLIED, account.getBalance());
        }

        if (type == OperationType.DEBIT) {
            account.withdraw(amount);
        } else {
            account.deposit(amount);
        }
        operations.save(new AccountOperation(operationId, accountId, type, amount));
        return new OperationOutcome(OperationOutcome.Status.APPLIED, account.getBalance());
    }

    // ------------------------------------------------------------------ user-facing API

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
