CREATE TABLE subject (
    id           UUID PRIMARY KEY,
    slug         VARCHAR(200) NOT NULL UNIQUE,
    name         VARCHAR(160) NOT NULL,
    category     VARCHAR(60)  NOT NULL,
    description  VARCHAR(2000),
    owner_id     UUID         NOT NULL,
    review_count BIGINT       NOT NULL DEFAULT 0,
    rating_sum   BIGINT       NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_subject_category ON subject (category);

CREATE TABLE review (
    id          UUID PRIMARY KEY,
    subject_id  UUID         NOT NULL REFERENCES subject (id),
    author_id   UUID         NOT NULL,
    author_name VARCHAR(80)  NOT NULL,
    title       VARCHAR(160) NOT NULL,
    body        VARCHAR(5000) NOT NULL,
    rating      INT          NOT NULL CHECK (rating BETWEEN 0 AND 100),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ux_review_one_per_author UNIQUE (subject_id, author_id)
);
CREATE INDEX ix_review_subject_created ON review (subject_id, created_at DESC);
CREATE INDEX ix_review_author ON review (author_id);

-- Transactional outbox: the event row is written in the SAME transaction as the business change,
-- then relayed to Kafka. No dual-write, no lost or phantom events.
CREATE TABLE outbox_event (
    id           UUID PRIMARY KEY,
    topic        VARCHAR(120) NOT NULL,
    event_key    VARCHAR(80)  NOT NULL,
    payload      TEXT         NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);
CREATE INDEX ix_outbox_unpublished ON outbox_event (created_at) WHERE published_at IS NULL;
