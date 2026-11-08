package com.fintech.wallet.notification.messaging;

/** The message can never be processed, however often it is retried: it goes straight to the dead-letter topic. */
public class MalformedEventException extends RuntimeException {

    public MalformedEventException(String message, Throwable cause) {
        super(message, cause);
    }

    public MalformedEventException(String message) {
        super(message);
    }
}
