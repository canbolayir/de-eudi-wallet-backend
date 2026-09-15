CREATE TABLE wpb_account
(
    id                    UUID PRIMARY KEY,
    wpb_account_id        UUID  NOT NULL UNIQUE,
    wi_mdvm_auth_pubk_der BYTEA NOT NULL
);
