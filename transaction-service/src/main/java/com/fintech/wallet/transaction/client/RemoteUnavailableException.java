package com.fintech.wallet.transaction.client;

/**
 * account-service did not give a usable answer (timeout, connection refused, 5xx).
 * The outcome of the call is UNKNOWN: it may or may not have been applied. Callers must retry with
 * the same operation id and never assume failure.
 */
public class RemoteUnavailableException extends RuntimeException {

    public RemoteUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
