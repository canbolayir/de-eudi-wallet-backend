ALTER TABLE rwsca_account
    ADD CONSTRAINT rwsca_pin_pubk_and_counter_null_together
        CHECK ((wi_rwsca_pin_pubk_der IS NULL) = (rwsca_pin_try_counter IS NULL));

ALTER TABLE rwsca_account
    ADD CONSTRAINT rwsca_pin_failed_at_requires_pin
        CHECK (wi_rwsca_pin_pubk_der IS NOT NULL OR rwsca_pin_try_failed_at IS NULL);
