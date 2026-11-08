CREATE TABLE transfers (
    id                UUID PRIMARY KEY,
    user_id           UUID           NOT NULL,
    idempotency_key   VARCHAR(64)    NOT NULL,
    from_account_id   UUID           NOT NULL,
    to_account_id     UUID           NOT NULL,
    amount            NUMERIC(19, 4) NOT NULL CHECK (amount > 0),
    currency          VARCHAR(3)     NOT NULL,
    description       VARCHAR(200),
    status            VARCHAR(20)    NOT NULL,
    failure_reason    VARCHAR(40),
    version           BIGINT         NOT NULL,
    created_at        TIMESTAMPTZ    NOT NULL,
    updated_at        TIMESTAMPTZ    NOT NULL,
    -- the database, not application code, guarantees one transfer per (user, idempotency key)
    CONSTRAINT uq_transfers_idempotency UNIQUE (user_id, idempotency_key),
    CONSTRAINT chk_transfers_distinct_accounts CHECK (from_account_id <> to_account_id)
);

-- used by the recovery job to find transfers that are not finished
CREATE INDEX idx_transfers_status_updated ON transfers (status, updated_at);
CREATE INDEX idx_transfers_user_created ON transfers (user_id, created_at DESC);

-- Double-entry ledger: every completed transfer writes one DEBIT and one CREDIT of the same amount.
-- Rows are only ever inserted, never updated or deleted.
CREATE TABLE ledger_entries (
    id          UUID PRIMARY KEY,
    transfer_id UUID           NOT NULL REFERENCES transfers (id),
    account_id  UUID           NOT NULL,
    direction   VARCHAR(6)     NOT NULL,
    amount      NUMERIC(19, 4) NOT NULL CHECK (amount > 0),
    currency    VARCHAR(3)     NOT NULL,
    created_at  TIMESTAMPTZ    NOT NULL,
    CONSTRAINT uq_ledger_transfer_direction UNIQUE (transfer_id, direction)
);

CREATE INDEX idx_ledger_account ON ledger_entries (account_id, created_at);
