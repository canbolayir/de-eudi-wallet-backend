ALTER TABLE rwsca_account
    ADD COLUMN wi_rwsca_pin_pubk_der BYTEA DEFAULT null;

ALTER TABLE rwsca_account
    ADD COLUMN rwsca_pin_try_counter SMALLINT DEFAULT null;

ALTER TABLE rwsca_account
    ADD CONSTRAINT rwsca_pin_try_counter_should_be_positive CHECK (rwsca_pin_try_counter >= 0);

ALTER TABLE rwsca_account
    ADD COLUMN rwsca_pin_try_failed_at TIMESTAMP DEFAULT null;
