package com.fintech.wallet.transaction.outbox;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The message published to Kafka when a transfer reaches a final state. This is a contract with consumers
 * (notification-service keeps its own copy): only add fields, never rename or remove them.
 *
 * @param eventId unique per event; consumers use it to ignore duplicates (delivery is at-least-once)
 * @param type    TRANSFER_COMPLETED, TRANSFER_FAILED or TRANSFER_REFUNDED
 */
public record TransferEvent(
        UUID eventId,
        String type,
        Instant occurredAt,
        UUID transferId,
        UUID fromUserId,
        UUID toUserId,
        UUID fromAccountId,
        UUID toAccountId,
        BigDecimal amount,
        String currency,
        String status,
        String failureReason) {
}
