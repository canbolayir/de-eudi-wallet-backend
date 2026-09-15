# SoftHSM

Wallet backend services can use [SoftHSM](https://github.com/softhsm/SoftHSMv2) for local development and testing.

## OSX ARM installation

```bash
git clone https://github.com/softhsm/SoftHSMv2
cd SoftHSMv2
brew install automake libtool pkg-config cppunit openssl
./autogen.sh
./configure --with-crypto-backend=openssl --with-openssl="$(brew --prefix openssl)"
make
sudo make install
```

Verify the installation

```bash
pkcs11-tool --module /usr/local/lib/softhsm/libsofthsm2.so -M -L
```

## Layout

```
softhsm/
  gen-keys.sh          # writes everything under generated/
  init-slot.sh         # imports generated/hsm-keys into the SoftHSM slot
  entrypoint.sh        # compose image: init-slot.sh, then the jar
  Dockerfile           # compose image: SoftHSM + the app jar (see docker-compose.yml)
  .gitignore           # ignores generated/
  generated/           # absent in a fresh clone
    softhsm2.conf      # SoftHSM config selecting the token store below; export it as SOFTHSM2_CONF
    tokens/            # this working copy's token store (the TEST_SLOT lives here, not system-wide)
    hsm-keys/          # keypairs + symmetric keys loaded into the slot
    dev-pki/ca/        # dev CA signing keys (sign the leaves; never loaded into the HSM or an image)
    dev-pki/roots/     # nw-root.pem, mdvm-root.pem: the roots the `dev-pki` profile pins (`DEV_PKI_DIR`)
    dev-pki/chains/    # leaf -> root chains seeded into the simulated S3 bucket: MDVM attestation under
                       # mdvm-root, WIA, WTE and status list under nw-root
```

Nothing under `generated/` is committed, and none of it reaches a deployed stage: those pin the real PKI through the
Helm chart and hold their keys in a real HSM. Only the local compose images bake the generated `hsm-keys/` in.

## Generating the fixtures

```bash
./softhsm/gen-keys.sh
```

Generates the symmetric keys and EC P-256 keypairs, mints the two dev roots and the leaf chains that wrap the keypairs'
public keys, and writes a SoftHSM config with a private token store. It refuses to run while
`generated/softhsm2.conf` exists: delete `generated/` to start over (this also drops the slot), run `gen-keys.sh`
again, then re-run `init-slot.sh`, re-run `docker compose up -d s3-init` so the bucket holds the new chains, and
rebuild the compose images (`docker compose up --build`) because they copy `hsm-keys/`.

Every SoftHSM client — `init-slot.sh`, the app, `softhsm2-util`, `pkcs11-tool` — finds the slot only with
`SOFTHSM2_CONF` pointing at the generated config. Both that path and the token store path inside the config are
relative to the repository root, so run the clients from there:

```bash
export SOFTHSM2_CONF=softhsm/generated/softhsm2.conf
softhsm2-util --show-slots
```

The compose image uses its own system-wide config instead: it is private to one container already.
