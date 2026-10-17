-- Profile fields used by the Drawback web app and phone verification.
-- The phone number itself is never stored: only a peppered hash (uniqueness: one number, one voter) and a masked label.
ALTER TABLE user_account
    ADD COLUMN bio          VARCHAR(160) NOT NULL DEFAULT '',
    ADD COLUMN location     VARCHAR(60),
    ADD COLUMN interests    VARCHAR(600) NOT NULL DEFAULT '',
    ADD COLUMN avatar_url   VARCHAR(500),
    ADD COLUMN onboarded    BOOLEAN      NOT NULL DEFAULT FALSE,
    ADD COLUMN phone_hash   VARCHAR(64),
    ADD COLUMN phone_masked VARCHAR(32);

CREATE UNIQUE INDEX ux_user_phone_hash ON user_account (phone_hash) WHERE phone_hash IS NOT NULL;
