DELETE
FROM rwsca_account a
    USING rwsca_account b
WHERE a.ctid < b.ctid
  AND a.wi_handle = b.wi_handle;

DELETE
FROM rwsca_account
WHERE wi_handle IS NULL;
ALTER TABLE rwsca_account
    ALTER COLUMN wi_handle SET NOT NULL;

DROP INDEX rwsca_account_wi_handle;
CREATE UNIQUE INDEX rwsca_account_wi_handle ON rwsca_account (wi_handle);
