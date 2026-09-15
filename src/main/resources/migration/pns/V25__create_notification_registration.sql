CREATE TABLE notification_registration
(
    id                     UUID PRIMARY KEY,
    account_id             UUID        NOT NULL UNIQUE,
    mpp_registration_token TEXT        NOT NULL,
    registered_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
