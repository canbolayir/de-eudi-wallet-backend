ALTER TABLE device_account
    ADD COLUMN version BIGINT;
UPDATE device_account
SET version = 0;
ALTER TABLE device_account
    ALTER COLUMN version SET NOT NULL;
