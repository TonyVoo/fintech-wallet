package com.fintech.wallet.account.domain;

public class OperationConflictException extends RuntimeException {

    public OperationConflictException(String operationId) {
        super("Operation " + operationId + " was already used for a different request");
    }
}
