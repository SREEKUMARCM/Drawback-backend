CREATE TABLE notification (
    id           UUID PRIMARY KEY,
    event_id     UUID         NOT NULL,
    recipient_id UUID         NOT NULL,
    type         VARCHAR(40)  NOT NULL,
    title        VARCHAR(200) NOT NULL,
    message      VARCHAR(500) NOT NULL,
    link         VARCHAR(300),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    read_at      TIMESTAMPTZ,
    -- idempotency: the same event can be delivered twice, but only creates one notification per recipient
    CONSTRAINT ux_notification_event_recipient UNIQUE (event_id, recipient_id)
);
CREATE INDEX ix_notification_recipient ON notification (recipient_id, created_at DESC);
