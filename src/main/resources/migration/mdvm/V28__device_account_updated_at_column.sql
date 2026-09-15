ALTER TABLE device_account
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
