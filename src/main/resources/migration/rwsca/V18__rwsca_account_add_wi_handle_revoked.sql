ALTER TABLE rwsca_account
    ADD COLUMN wi_handle  TEXT,
    ADD COLUMN revoked_at TIMESTAMPTZ;

CREATE INDEX rwsca_account_wi_handle ON rwsca_account (wi_handle);
