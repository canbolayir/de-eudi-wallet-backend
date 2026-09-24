#!/usr/bin/env bash
set -euo pipefail

export PGHOST="${PGHOST:-127.0.0.1}"
export PGPORT="${PGPORT:-5432}"
export PGDATABASE="${PGDATABASE:-wallet}"
export PGUSER="${PGUSER:-wallet}"
export PGPASSWORD="${PGPASSWORD:-wallet}"

psql_cmd() {
  psql -X -v ON_ERROR_STOP=1 "$@"
}

echo "[1/6] Applying the repository's real RWSCA migrations..."
psql_cmd -f src/main/resources/migration/rwsca/V10__create_rwsca_account.sql
psql_cmd -f src/main/resources/migration/rwsca/V11__rwsca_add_pin.sql
psql_cmd -f src/main/resources/migration/rwsca/V12__rwsca_add_pin_nullability_constraint.sql
psql_cmd -f src/main/resources/migration/rwsca/V18__rwsca_account_add_wi_handle_revoked.sql
psql_cmd -f src/main/resources/migration/rwsca/V22__rwsca_account_unique_key.sql
psql_cmd -f src/main/resources/migration/rwsca/V30__rwsca_account_mdvm_wi_id.sql

echo "[2/6] Creating the initial active RWSCA account..."
psql_cmd -c "INSERT INTO rwsca_account
(id, rwsca_account_id, wi_mdvm_auth_pubk_der, wi_handle, mdvm_wi_id)
VALUES
('00000000-0000-0000-0000-000000000001',
 '11111111-1111-1111-1111-111111111111',
 decode('04','hex'),
 'poc-wallet-handle',
 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa');"

echo "[3/6] Deleting the RWSCA account before wallet revocation..."
deleted_count="$(psql_cmd -Atc "WITH deleted AS (
  DELETE FROM rwsca_account
  WHERE rwsca_account_id = '11111111-1111-1111-1111-111111111111'
  RETURNING 1
) SELECT count(*) FROM deleted;")"
test "$deleted_count" = "1" || { echo "FAIL: initial account was not deleted"; exit 1; }

echo "[4/6] Replaying the exact revoke-by-wi_handle persistence semantics..."
revoked_count="$(psql_cmd -Atc "WITH updated AS (
  UPDATE rwsca_account
  SET revoked_at = now()
  WHERE wi_handle = 'poc-wallet-handle' AND revoked_at IS NULL
  RETURNING 1
) SELECT count(*) FROM updated;")"

already_revoked_count="$(psql_cmd -Atc "SELECT count(*)
FROM rwsca_account
WHERE wi_handle = 'poc-wallet-handle' AND revoked_at IS NOT NULL;")"

test "$revoked_count" = "0" || { echo "FAIL: revocation unexpectedly updated a row"; exit 1; }
test "$already_revoked_count" = "0" || { echo "FAIL: durable revoked state unexpectedly exists"; exit 1; }

echo "    Result matches RwscaAccountService.revokeByWiHandle(): UNKNOWN_HANDLE"

echo "[5/6] Re-registering the same wallet identity after the revocation event..."
psql_cmd -c "INSERT INTO rwsca_account
(id, rwsca_account_id, wi_mdvm_auth_pubk_der, wi_handle, mdvm_wi_id)
VALUES
('00000000-0000-0000-0000-000000000002',
 '22222222-2222-2222-2222-222222222222',
 decode('04','hex'),
 'poc-wallet-handle',
 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa');"

active_count="$(psql_cmd -Atc "SELECT count(*)
FROM rwsca_account
WHERE wi_handle = 'poc-wallet-handle'
  AND mdvm_wi_id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
  AND revoked_at IS NULL;")"

test "$active_count" = "1" || { echo "FAIL: resurrected active account was not created"; exit 1; }

echo "[6/6] Negative control: without pre-revocation deletion, resurrection is blocked..."
psql_cmd -c "TRUNCATE TABLE rwsca_account;"
psql_cmd -c "INSERT INTO rwsca_account
(id, rwsca_account_id, wi_mdvm_auth_pubk_der, wi_handle, mdvm_wi_id)
VALUES
('00000000-0000-0000-0000-000000000003',
 '33333333-3333-3333-3333-333333333333',
 decode('04','hex'),
 'negative-control-handle',
 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb');"

negative_revoke_count="$(psql_cmd -Atc "WITH updated AS (
  UPDATE rwsca_account
  SET revoked_at = now()
  WHERE wi_handle = 'negative-control-handle' AND revoked_at IS NULL
  RETURNING 1
) SELECT count(*) FROM updated;")"
test "$negative_revoke_count" = "1" || { echo "FAIL: negative-control revocation did not apply"; exit 1; }

set +e
psql_cmd -c "INSERT INTO rwsca_account
(id, rwsca_account_id, wi_mdvm_auth_pubk_der, wi_handle, mdvm_wi_id)
VALUES
('00000000-0000-0000-0000-000000000004',
 '44444444-4444-4444-4444-444444444444',
 decode('04','hex'),
 'negative-control-handle',
 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb');" >/tmp/rwsca-negative-control.out 2>&1
negative_insert_rc=$?
set -e

if [ "$negative_insert_rc" -eq 0 ]; then
  echo "FAIL: negative control unexpectedly allowed a second account"
  cat /tmp/rwsca-negative-control.out
  exit 1
fi

echo
echo "CONFIRMED:"
echo "delete-before-revocation -> no durable revoked row -> same wi_handle/mdvm_wi_id can be registered again"
echo "NEGATIVE CONTROL PASSED:"
echo "without deletion, the revoked row remains and the repository's unique constraints block re-registration"
