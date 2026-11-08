CREATE TABLE notifications (
    id          UUID PRIMARY KEY,
    event_id    UUID         NOT NULL,
    user_id     UUID         NOT NULL,
    transfer_id UUID         NOT NULL,
    kind        VARCHAR(30)  NOT NULL,
    message     VARCHAR(300) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    -- Kafka delivers at-least-once, so the same event can arrive twice. This constraint is the last line of
    -- defence that guarantees one notification per user per event, however often it is redelivered.
    CONSTRAINT uq_notifications_event_user UNIQUE (event_id, user_id)
);

CREATE INDEX idx_notifications_user_created ON notifications (user_id, created_at DESC);
