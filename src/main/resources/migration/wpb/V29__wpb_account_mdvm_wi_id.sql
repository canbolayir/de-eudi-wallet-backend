ALTER TABLE wpb_account
    ADD COLUMN mdvm_wi_id UUID;
CREATE UNIQUE INDEX wpb_account_mdvm_wi_id ON wpb_account (mdvm_wi_id);
