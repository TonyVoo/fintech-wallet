-- The user who owns the destination account, so the receiver can be notified.
-- Nullable only so transfers created before this migration survive; new transfers always set it.
ALTER TABLE transfers ADD COLUMN to_user_id UUID;
