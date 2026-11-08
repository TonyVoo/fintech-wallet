package com.fintech.wallet.transaction.api;

import com.fintech.wallet.transaction.client.RemoteUnavailableException;
import com.fintech.wallet.transaction.service.TransferRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(TransferRejectedException.class)
    public ProblemDetail rejected(TransferRejectedException ex) {
        return ProblemDetail.forStatusAndDetail(ex.status(), ex.getMessage());
    }

    /** Only reachable before a transfer exists (account lookup); nothing has moved, so the client can just retry. */
    @ExceptionHandler(RemoteUnavailableException.class)
    public ProblemDetail remoteUnavailable(RemoteUnavailableException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "A required service is temporarily unavailable, please retry");
    }
}
