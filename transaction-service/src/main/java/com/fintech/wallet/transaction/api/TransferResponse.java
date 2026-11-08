package com.fintech.wallet.transaction.api;

import com.fintech.wallet.transaction.domain.FailureReason;
import com.fintech.wallet.transaction.domain.Transfer;
import com.fintech.wallet.transaction.domain.TransferStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransferResponse(
        UUID id,
        TransferStatus status,
        FailureReason failureReason,
        UUID fromAccountId,
        UUID toAccountId,
        BigDecimal amount,
        String currency,
        String description,
        Instant createdAt,
        Instant updatedAt) {

    public static TransferResponse from(Transfer t) {
        return new TransferResponse(t.getId(), t.getStatus(), t.getFailureReason(), t.getFromAccountId(),
                t.getToAccountId(), t.getAmount(), t.getCurrency(), t.getDescription(),
                t.getCreatedAt(), t.getUpdatedAt());
    }
}
