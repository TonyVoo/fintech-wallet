package com.fintech.wallet.account.domain;

public class InsufficientFundsException extends RuntimeException {

    public InsufficientFundsException(String accountNumber) {
        super("Insufficient funds in account " + accountNumber);
    }
}
