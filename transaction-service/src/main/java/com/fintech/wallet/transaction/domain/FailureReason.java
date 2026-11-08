package com.fintech.wallet.transaction.domain;

public enum FailureReason {
    INSUFFICIENT_FUNDS,
    SOURCE_ACCOUNT_NOT_FOUND,
    DESTINATION_ACCOUNT_UNAVAILABLE
}
