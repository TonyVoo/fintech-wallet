package com.fintech.wallet.notification.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * This service's own copy of the event published by transaction-service. There is no shared library on purpose:
 * services stay independently deployable, and the contract is "producers only add fields". Unknown fields are
 * ignored so a newer producer does not break an older consumer.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TransferEvent(
        UUID eventId,
        String type,
        Instant occurredAt,
        UUID transferId,
        UUID fromUserId,
        UUID toUserId,
        BigDecimal amount,
        String currency,
        String status,
        String failureReason) {
}
