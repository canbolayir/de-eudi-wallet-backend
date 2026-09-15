CREATE TABLE rwsca_account (
    id                    UUID  PRIMARY KEY,
    rwsca_account_id      UUID  NOT NULL UNIQUE,
    wi_mdvm_auth_pubk_der BYTEA NOT NULL
);
