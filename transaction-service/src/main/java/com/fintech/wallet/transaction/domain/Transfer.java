package com.fintech.wallet.transaction.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transfers")
public class Transfer {

    @Id
    private UUID id;

    /** The user who asked for the transfer (JWT subject). */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "idempotency_key", nullable = false, length = 64)
    private String idempotencyKey;

    @Column(name = "from_account_id", nullable = false)
    private UUID fromAccountId;

    @Column(name = "to_account_id", nullable = false)
    private UUID toAccountId;

    /** Owner of the destination account; used to notify the receiver. */
    @Column(name = "to_user_id")
    private UUID toUserId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(length = 200)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransferStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_reason", length = 40)
    private FailureReason failureReason;

    /** Wrapper type on purpose: null means "not saved yet", so Spring Data does a plain INSERT. */
    @Version
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Transfer() {
        // for JPA
    }

    public Transfer(UUID userId, String idempotencyKey, UUID fromAccountId, UUID toAccountId, UUID toUserId,
                    BigDecimal amount, String currency, String description) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.idempotencyKey = idempotencyKey;
        this.fromAccountId = fromAccountId;
        this.toAccountId = toAccountId;
        this.toUserId = toUserId;
        this.amount = amount;
        this.currency = currency;
        this.description = description;
        this.status = TransferStatus.PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    // ------------------------------------------------------------------ state transitions

    public void markDebited() {
        require(TransferStatus.PENDING);
        moveTo(TransferStatus.DEBITED, null);
    }

    public void markCompleted() {
        require(TransferStatus.DEBITED);
        moveTo(TransferStatus.COMPLETED, null);
    }

    public void markFailed(FailureReason reason) {
        require(TransferStatus.PENDING);
        moveTo(TransferStatus.FAILED, reason);
    }

    public void markRefunded(FailureReason reason) {
        require(TransferStatus.DEBITED);
        moveTo(TransferStatus.REFUNDED, reason);
    }

    private void require(TransferStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("Transfer " + id + " is " + status + ", expected " + expected);
        }
    }

    private void moveTo(TransferStatus next, FailureReason reason) {
        this.status = next;
        this.failureReason = reason;
        this.updatedAt = Instant.now();
    }

    // ------------------------------------------------------------------ idempotency

    /** True if a request carries the same content as this transfer (used when an Idempotency-Key repeats). */
    public boolean matches(UUID fromAccountId, UUID toAccountId, BigDecimal amount) {
        return this.fromAccountId.equals(fromAccountId)
                && this.toAccountId.equals(toAccountId)
                && this.amount.compareTo(amount) == 0;
    }

    /**
     * Operation ids sent to account-service. They are derived from the transfer id, so every retry of the
     * same step (after a timeout, a crash, a recovery run) uses the same id and account-service applies it once.
     */
    public String debitOperationId() {
        return "transfer-" + id + "-debit";
    }

    public String creditOperationId() {
        return "transfer-" + id + "-credit";
    }

    public String refundOperationId() {
        return "transfer-" + id + "-refund";
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public UUID getFromAccountId() { return fromAccountId; }
    public UUID getToAccountId() { return toAccountId; }
    public UUID getToUserId() { return toUserId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getDescription() { return description; }
    public TransferStatus getStatus() { return status; }
    public FailureReason getFailureReason() { return failureReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
