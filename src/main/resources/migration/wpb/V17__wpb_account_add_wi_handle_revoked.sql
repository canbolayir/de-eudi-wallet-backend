ALTER TABLE wpb_account
    ADD COLUMN wi_handle  TEXT,
    ADD COLUMN revoked_at TIMESTAMPTZ;

CREATE INDEX wpb_account_wi_handle ON wpb_account (wi_handle);
