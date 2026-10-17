-- Drawbacks: a problem report about a subject. Other users vote 0-100% agreement, each vote with a mandatory comment.
-- Community Agreement Score = vote_sum / vote_count (arithmetic mean). Aggregates are maintained with atomic UPDATEs.

-- SubjectEntity maps an optimistic-lock @Version column that V1 never created (schema validation would fail without it).
ALTER TABLE subject ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE subject
    ADD COLUMN brand             VARCHAR(120),
    ADD COLUMN subject_type      VARCHAR(30) NOT NULL DEFAULT 'other',
    ADD COLUMN identity_verified BOOLEAN     NOT NULL DEFAULT FALSE;
CREATE INDEX ix_subject_name_lower ON subject (lower(name));

-- Local read model of the users this service has seen (author names on posts, profile pages).
-- Source of truth is auth-service; rows are refreshed over gRPC when stale (see UserSnapshotService).
CREATE TABLE user_snapshot (
    id           UUID PRIMARY KEY,
    username     VARCHAR(30)  NOT NULL,
    display_name VARCHAR(80)  NOT NULL,
    verified     BOOLEAN      NOT NULL DEFAULT FALSE,
    avatar_url   VARCHAR(500),
    bio          VARCHAR(160) NOT NULL DEFAULT '',
    location     VARCHAR(60),
    interests    VARCHAR(600) NOT NULL DEFAULT '',
    roles        VARCHAR(60)  NOT NULL DEFAULT 'USER',
    joined_at    TIMESTAMPTZ,
    suspended    BOOLEAN      NOT NULL DEFAULT FALSE,
    onboarded    BOOLEAN      NOT NULL DEFAULT FALSE,
    refreshed_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_user_snapshot_username ON user_snapshot (lower(username));

CREATE TABLE drawback (
    id            UUID PRIMARY KEY,
    author_id     UUID          NOT NULL,
    subject_id    UUID          NOT NULL REFERENCES subject (id),
    category      VARCHAR(60)   NOT NULL,
    headline      VARCHAR(200)  NOT NULL,
    description   VARCHAR(8000) NOT NULL,
    location      VARCHAR(120),
    purchase_date VARCHAR(40),
    reference     VARCHAR(160),
    visibility    VARCHAR(12)   NOT NULL DEFAULT 'public' CHECK (visibility IN ('public', 'followers')),
    status        VARCHAR(12)   NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'disputed', 'removed')),
    official_org  VARCHAR(160),
    official_text VARCHAR(4000),
    official_at   TIMESTAMPTZ,
    vote_count    INT           NOT NULL DEFAULT 0,
    vote_sum      BIGINT        NOT NULL DEFAULT 0,
    reply_count   INT           NOT NULL DEFAULT 0,
    dist0         INT           NOT NULL DEFAULT 0,   -- votes of 0-20 %
    dist1         INT           NOT NULL DEFAULT 0,   -- 21-40 %
    dist2         INT           NOT NULL DEFAULT 0,   -- 41-60 %
    dist3         INT           NOT NULL DEFAULT 0,   -- 61-80 %
    dist4         INT           NOT NULL DEFAULT 0,   -- 81-100 %
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_drawback_created ON drawback (created_at DESC) WHERE status <> 'removed';
CREATE INDEX ix_drawback_subject ON drawback (subject_id);
CREATE INDEX ix_drawback_author  ON drawback (author_id, created_at DESC);
CREATE INDEX ix_drawback_category ON drawback (category);

CREATE TABLE drawback_attachment (
    id          UUID PRIMARY KEY,                       -- = media.id
    drawback_id UUID         NOT NULL REFERENCES drawback (id) ON DELETE CASCADE,
    position    INT          NOT NULL,
    kind        VARCHAR(8)   NOT NULL CHECK (kind IN ('image', 'video')),
    url         VARCHAR(500) NOT NULL,
    poster      VARCHAR(500),
    caption     VARCHAR(200),
    name        VARCHAR(200)
);
CREATE INDEX ix_attachment_drawback ON drawback_attachment (drawback_id, position);

CREATE TABLE vote (
    id           UUID PRIMARY KEY,
    drawback_id  UUID         NOT NULL REFERENCES drawback (id) ON DELETE CASCADE,
    author_id    UUID         NOT NULL,
    percent      SMALLINT     NOT NULL CHECK (percent BETWEEN 0 AND 100),
    comment_text VARCHAR(2000) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ux_vote_one_per_author UNIQUE (drawback_id, author_id)
);
CREATE INDEX ix_vote_drawback_created ON vote (drawback_id, created_at);
CREATE INDEX ix_vote_author ON vote (author_id, created_at DESC);

CREATE TABLE vote_reply (
    id         UUID PRIMARY KEY,
    vote_id    UUID         NOT NULL REFERENCES vote (id) ON DELETE CASCADE,
    author_id  UUID         NOT NULL,
    body       VARCHAR(600) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_reply_vote ON vote_reply (vote_id, created_at);

CREATE TABLE saved_drawback (
    user_id     UUID        NOT NULL,
    drawback_id UUID        NOT NULL REFERENCES drawback (id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, drawback_id)
);

-- kind: users (target_key = lowercase username) | categories (slug) | subjects (slug)
CREATE TABLE follow (
    user_id    UUID         NOT NULL,
    kind       VARCHAR(12)  NOT NULL CHECK (kind IN ('users', 'categories', 'subjects')),
    target_key VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, kind, target_key)
);
CREATE INDEX ix_follow_target ON follow (kind, target_key);

CREATE TABLE user_settings (
    user_id    UUID PRIMARY KEY,
    json       TEXT        NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Moderation
CREATE SEQUENCE report_case_seq START 2042;
CREATE TABLE report_case (
    id          VARCHAR(24) PRIMARY KEY DEFAULT ('CASE-' || nextval('report_case_seq')),
    target_type VARCHAR(16)  NOT NULL CHECK (target_type IN ('post', 'comment', 'voting-pattern')),
    drawback_id UUID         NOT NULL,                  -- no FK on purpose: the case outlives a deleted post
    vote_id     UUID,
    headline    VARCHAR(200) NOT NULL,
    reason      VARCHAR(120) NOT NULL,
    reports     INT          NOT NULL DEFAULT 1,
    status      VARCHAR(12)  NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'reviewing', 'resolved')),
    priority    VARCHAR(8)   NOT NULL DEFAULT 'low' CHECK (priority IN ('low', 'medium', 'high')),
    signals     TEXT         NOT NULL DEFAULT '',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_case_status ON report_case (status, created_at DESC);
CREATE INDEX ix_case_drawback ON report_case (drawback_id);

CREATE TABLE report_case_log (
    id      UUID PRIMARY KEY,
    case_id VARCHAR(24)   NOT NULL REFERENCES report_case (id) ON DELETE CASCADE,
    at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    actor   VARCHAR(80)   NOT NULL,
    action  VARCHAR(80)   NOT NULL,
    note    VARCHAR(1000) NOT NULL
);
CREATE INDEX ix_case_log_case ON report_case_log (case_id, at);

-- Uploaded evidence. Files live on disk (see drawback.media.dir); only metadata is stored here.
CREATE TABLE media (
    id            UUID PRIMARY KEY,
    owner_id      UUID         NOT NULL,
    kind          VARCHAR(8)   NOT NULL CHECK (kind IN ('image', 'video')),
    content_type  VARCHAR(80)  NOT NULL,
    original_name VARCHAR(200) NOT NULL,
    size_bytes    BIGINT       NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_media_owner ON media (owner_id);
