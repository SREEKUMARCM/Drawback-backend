CREATE TABLE user_account (
    id            UUID PRIMARY KEY,
    username      VARCHAR(30)  NOT NULL,
    email         VARCHAR(254) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    display_name  VARCHAR(80)  NOT NULL,
    verified      BOOLEAN      NOT NULL DEFAULT FALSE,
    status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version       BIGINT       NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_user_username_lower ON user_account (lower(username));
CREATE UNIQUE INDEX ux_user_email_lower    ON user_account (lower(email));

CREATE TABLE user_roles (
    user_id UUID        NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    role    VARCHAR(16) NOT NULL,
    PRIMARY KEY (user_id, role)
);

CREATE TABLE user_stats (
    user_id      UUID PRIMARY KEY,
    review_count BIGINT NOT NULL DEFAULT 0
);

-- Idempotency ledger for Kafka consumers (at-least-once delivery => the same event can arrive twice)
CREATE TABLE processed_event (
    event_id     UUID        NOT NULL,
    consumer     VARCHAR(64) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, consumer)
);
