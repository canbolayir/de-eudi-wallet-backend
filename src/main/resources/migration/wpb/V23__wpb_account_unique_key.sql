
DELETE
FROM wpb_account a
    USING wpb_account b
WHERE a.ctid < b.ctid
  AND a.wi_handle = b.wi_handle;

DELETE
FROM wpb_account
WHERE wi_handle IS NULL;
ALTER TABLE wpb_account
    ALTER COLUMN wi_handle SET NOT NULL;

DROP INDEX wpb_account_wi_handle;
CREATE UNIQUE INDEX wpb_account_wi_handle ON wpb_account (wi_handle);
