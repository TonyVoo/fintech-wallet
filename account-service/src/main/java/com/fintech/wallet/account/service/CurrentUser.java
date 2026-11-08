package com.fintech.wallet.account.service;

import com.fintech.wallet.account.domain.Account;

import java.util.UUID;

/** The caller, as far as the service layer cares: who they are and whether they are an admin. */
public record CurrentUser(UUID id, boolean admin) {

    public boolean canAccess(Account account) {
        return admin || id.equals(account.getOwnerId());
    }
}
