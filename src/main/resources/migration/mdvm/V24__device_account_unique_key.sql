
DELETE
FROM device_account a
    USING device_account b
WHERE a.ctid < b.ctid
  AND a.wi_handle = b.wi_handle;

DELETE
FROM device_account
WHERE wi_handle IS NULL;
ALTER TABLE device_account
    ALTER COLUMN wi_handle SET NOT NULL;

DROP INDEX device_account_wi_handle;
CREATE UNIQUE INDEX device_account_wi_handle ON device_account (wi_handle);
