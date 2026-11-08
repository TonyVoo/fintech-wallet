-- One row per balance change requested by another service (e.g. transaction-service).
-- The caller-chosen operation_id makes those requests idempotent: sending the same
-- operation twice (after a timeout, a retry, a crash) changes the balance only once.
CREATE TABLE account_operations (
    operation_id VARCHAR(100)   PRIMARY KEY,
    account_id   UUID           NOT NULL REFERENCES accounts (id),
    type         VARCHAR(10)    NOT NULL,
    amount       NUMERIC(19, 4) NOT NULL CHECK (amount > 0),
    created_at   TIMESTAMPTZ    NOT NULL
);

CREATE INDEX idx_account_operations_account_id ON account_operations (account_id);
