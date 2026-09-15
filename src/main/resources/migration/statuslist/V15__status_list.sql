CREATE TABLE status_list
(
    id      UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
    pool    TEXT        NOT NULL,
    bits_per_entry SMALLINT NOT NULL,
    size    INTEGER     NOT NULL,
    cursor  INTEGER     NOT NULL DEFAULT 0,
    seed    BYTEA       NOT NULL,
    data    BYTEA       NOT NULL,
    version INTEGER     NOT NULL DEFAULT 0,
    created TIMESTAMP   NOT NULL DEFAULT now(),
    exhausted_at TIMESTAMP
);

CREATE UNIQUE INDEX one_current_per_pool ON status_list (pool) WHERE cursor < size;
CREATE INDEX status_list_pool_idx ON status_list (pool);

CREATE TABLE status_list_entry
(
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id UUID      NOT NULL,
    list_id    UUID      NOT NULL,
    idx        INTEGER   NOT NULL,
    exp        TIMESTAMP NOT NULL,
    created    TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX status_list_entry_account_idx ON status_list_entry (account_id);
CREATE INDEX status_list_entry_exp_idx ON status_list_entry (exp);
