package com.fintech.wallet.account.repository;

import com.fintech.wallet.account.domain.AccountOperation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountOperationRepository extends JpaRepository<AccountOperation, String> {
}
