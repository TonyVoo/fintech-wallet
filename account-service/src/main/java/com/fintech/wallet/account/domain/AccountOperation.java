package com.fintech.wallet.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Record that an operation id was applied to an account.
 *
 * <p>The id is assigned by the caller, so Spring Data would treat {@code save()} as a merge
 * (SELECT then UPDATE) and silently overwrite a duplicate. Implementing {@link Persistable} forces a real
 * INSERT, so a concurrent duplicate hits the primary key and its whole transaction, balance change
 * included, is rolled back.
 */
@Entity
@Table(name = "account_operations")
public class AccountOperation implements Persistable<String> {

    @Id
    @Column(name = "operation_id", length = 100)
    private String operationId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private OperationType type;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Transient
    private boolean newEntity = true;

    protected AccountOperation() {
        // for JPA
    }

    public AccountOperation(String operationId, UUID accountId, OperationType type, BigDecimal amount) {
        this.operationId = operationId;
        this.accountId = accountId;
        this.type = type;
        this.amount = amount;
        this.createdAt = Instant.now();
    }

    /** True if this stored operation is exactly the one being requested again. */
    public boolean matches(UUID accountId, OperationType type, BigDecimal amount) {
        return this.accountId.equals(accountId) && this.type == type && this.amount.compareTo(amount) == 0;
    }

    @Override
    public String getId() {
        return operationId;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.newEntity = false;
    }
}
