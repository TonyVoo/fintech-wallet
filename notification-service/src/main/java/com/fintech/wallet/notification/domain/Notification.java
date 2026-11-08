package com.fintech.wallet.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "transfer_id", nullable = false)
    private UUID transferId;

    @Column(nullable = false, length = 30)
    private String kind;

    @Column(nullable = false, length = 300)
    private String message;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Notification() {
        // for JPA
    }

    public Notification(UUID eventId, UUID userId, UUID transferId, String kind, String message) {
        this.id = UUID.randomUUID();
        this.eventId = eventId;
        this.userId = userId;
        this.transferId = transferId;
        this.kind = kind;
        this.message = message;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getEventId() { return eventId; }
    public UUID getUserId() { return userId; }
    public UUID getTransferId() { return transferId; }
    public String getKind() { return kind; }
    public String getMessage() { return message; }
    public Instant getCreatedAt() { return createdAt; }
}
