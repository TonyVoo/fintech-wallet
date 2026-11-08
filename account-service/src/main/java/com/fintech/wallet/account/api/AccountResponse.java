package com.fintech.wallet.account.api;

import com.fintech.wallet.account.domain.Account;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AccountResponse(
        UUID id,
        String accountNumber,
        String ownerName,
        String currency,
        BigDecimal balance,
        Instant createdAt) {

    public static AccountResponse from(Account a) {
        return new AccountResponse(a.getId(), a.getAccountNumber(), a.getOwnerName(),
                a.getCurrency(), a.getBalance(), a.getCreatedAt());
    }
}
