ALTER TABLE device_account
    ADD COLUMN wi_handle  TEXT,
    ADD COLUMN revoked_at TIMESTAMPTZ;

CREATE INDEX device_account_wi_handle ON device_account (wi_handle);
