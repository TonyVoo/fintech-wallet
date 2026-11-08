package com.fintech.wallet.notification.api;

import com.fintech.wallet.notification.domain.Notification;

import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(UUID id, UUID transferId, String kind, String message, Instant createdAt) {

    public static NotificationResponse from(Notification n) {
        return new NotificationResponse(n.getId(), n.getTransferId(), n.getKind(), n.getMessage(), n.getCreatedAt());
    }
}
