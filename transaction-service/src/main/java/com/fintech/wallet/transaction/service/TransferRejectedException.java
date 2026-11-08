package com.fintech.wallet.transaction.service;

import org.springframework.http.HttpStatus;

/** A request that is refused before any money moves. */
public class TransferRejectedException extends RuntimeException {

    private final HttpStatus status;

    private TransferRejectedException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    public static TransferRejectedException invalid(String message) {
        return new TransferRejectedException(HttpStatus.BAD_REQUEST, message);
    }

    /** Someone else's account is reported as not found, so account ids cannot be probed. */
    public static TransferRejectedException sourceNotFound() {
        return new TransferRejectedException(HttpStatus.NOT_FOUND, "Source account not found");
    }

    public static TransferRejectedException destinationNotFound() {
        return new TransferRejectedException(HttpStatus.NOT_FOUND, "Destination account not found");
    }

    public static TransferRejectedException transferNotFound() {
        return new TransferRejectedException(HttpStatus.NOT_FOUND, "Transfer not found");
    }

    public static TransferRejectedException currencyMismatch() {
        return new TransferRejectedException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Source and destination accounts use different currencies");
    }

    public static TransferRejectedException idempotencyKeyReused() {
        return new TransferRejectedException(HttpStatus.UNPROCESSABLE_ENTITY,
                "This Idempotency-Key was already used for a different request");
    }
}
