CREATE TABLE device_account
(
    id                                      UUID PRIMARY KEY,
    mdvm_wi_id                              UUID  NOT NULL UNIQUE,
    wi_mdvm_auth_pubk_der                   BYTEA NOT NULL,
    wi_mdvm_auth_pubk_attestation_chain_pem TEXT,
    device_type                             TEXT  NOT NULL,
    device_class                            JSONB NOT NULL,
    pap_playintegrity_attestation_payload   JSONB,
    pap_devicecheck_attestation             BYTEA
);
