ALTER TABLE wpb_account
    ADD COLUMN wpb_wi_revocation_hash BYTEA;

CREATE UNIQUE INDEX wpb_account_wpb_wi_revocation_hash ON wpb_account (wpb_wi_revocation_hash);
