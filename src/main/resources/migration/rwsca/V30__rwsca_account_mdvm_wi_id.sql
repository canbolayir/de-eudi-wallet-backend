ALTER TABLE rwsca_account
    ADD COLUMN mdvm_wi_id UUID;
CREATE UNIQUE INDEX rwsca_account_mdvm_wi_id ON rwsca_account (mdvm_wi_id);
