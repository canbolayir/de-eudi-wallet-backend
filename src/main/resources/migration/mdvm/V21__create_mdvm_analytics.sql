CREATE TABLE mdvm_analytics
(
    id                    UUID PRIMARY KEY,
    operation_name        TEXT        NOT NULL,
    auth_challenge        TEXT        NOT NULL,
    skip_integrity_checks TEXT        NOT NULL,
    request               JSONB       NOT NULL,
    exception_details     JSONB,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    version               BIGINT      NOT NULL DEFAULT 0
);
