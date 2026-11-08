package com.fintech.wallet.account.repository;

import com.fintech.wallet.account.domain.Account;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    boolean existsByAccountNumber(String accountNumber);

    List<Account> findByOwnerIdOrderByCreatedAtDesc(UUID ownerId);
}
