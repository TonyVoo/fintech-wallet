package com.fintech.wallet.account.service;

import java.math.BigDecimal;

public record OperationOutcome(Status status, BigDecimal balance) {

    public enum Status {
        /** The balance was changed by this call. */
        APPLIED,
        /** The same operation had been applied before; nothing changed this time. */
        ALREADY_APPLIED
    }
}
