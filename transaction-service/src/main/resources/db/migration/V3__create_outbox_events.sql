-- Transactional outbox: an event is inserted in the SAME database transaction as the state change that caused it,
-- so "the transfer is completed" and "the event exists" are committed together or not at all.
-- A separate publisher then forwards unpublished rows to Kafka (at-least-once).
CREATE TABLE outbox_events (
    id           UUID PRIMARY KEY,
    aggregate_id UUID        NOT NULL,
    type         VARCHAR(40) NOT NULL,
    payload      TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ
);

-- the publisher only ever looks at unpublished rows, oldest first
CREATE INDEX idx_outbox_unpublished ON outbox_events (created_at) WHERE published_at IS NULL;
