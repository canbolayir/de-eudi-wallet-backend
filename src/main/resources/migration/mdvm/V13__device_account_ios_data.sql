ALTER TABLE device_account
    DROP COLUMN pap_devicecheck_attestation;

ALTER TABLE device_account
    ADD COLUMN ios_devicecheck_attestation JSONB;

ALTER TABLE device_account
    ADD COLUMN ios_devicecheck_assertion JSONB;
