-- The user (auth-service user id, the JWT "sub") who owns the account.
-- Nullable only so accounts created before authentication existed survive the migration;
-- the application always sets it, and such legacy rows are unreachable through the API.
ALTER TABLE accounts ADD COLUMN owner_id UUID;

CREATE INDEX idx_accounts_owner_id ON accounts (owner_id);
