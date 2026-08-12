# canton-dapp-server

The reference **Canton dApp server** — the public backend a wallet reaches from
the outside. A real dApp is a service on the internet, not a listener on a
phone; this is that service. It runs next to a Splice LocalNet and is built on
the official [`@canton-network`](https://www.npmjs.com/org/canton-network) SDKs,
so it doubles as an independent, ecosystem-standard implementation that our
wallet answers to.

It is the server end of the corrected architecture (see the repo
[README](../README.md#architecture--how-a-dapp-reaches-a-wallet)): **both phone
apps are clients; the server is public.** The wallet dials out to it.

## What it does today

**Sign in with Canton** (dApp implementation spec §7.2) — a dApp proves a user
controls a Canton party by having the wallet sign a structured challenge, which
this server verifies against the party's public key. It is a convention over
CIP-0103 `signMessage`, modelled on Sign-In with Ethereum (ERC-4361), and the
first *external* consumer of the SDK's `DappSignMessage` domain-separation
scheme — the same 38-byte prefix the Kotlin and Swift wallets sign, verified
here byte-for-byte against the [shared golden vector](../../canton-mobile-sdk/testdata/dapp/signmessage.json).

| Method | Path | Body | Result |
|---|---|---|---|
| `GET`  | `/health` | — | service, domain, network, live nonce count |
| `POST` | `/siwc/challenge` | `{ party, publicKey, uri?, statement? }` | `{ message, nonce, expiresAt }` |
| `POST` | `/siwc/verify` | `{ message, signature }` | `200 { ok, party }` or `401 { ok:false, reason }` |

`publicKey` is the party's SPKI public key (DER, hex) — exactly what the
wallet's `listAccounts` publishes. It is bound to the nonce at challenge time,
so `/siwc/verify` checks the signature against that key rather than trusting one
from the verify request. `signature` is hex.

### The flow

```
dApp                          this server                      wallet
 │  (connect / listAccounts) ─────────────────────────────────►│  party + publicKey
 │  POST /siwc/challenge ────►│ issue nonce, build message      │
 │◄──────── message ─────────│                                  │
 │  signMessage(message) ─────────────────────────────────────►│ approve + sign in enclave
 │◄──────── signature ────────────────────────────────────────│
 │  POST /siwc/verify ───────►│ verify sig vs key · domain ·    │
 │◄──────── { ok, party } ───│ nonce (single-use) · freshness  │
```

The verification checks, all of which must pass: the signature is valid over
`DappSignMessage.signingBytes(message)` against the party's key (Ed25519 or
P-256); the message names this server's `domain` (anti cross-site replay) and
network; the nonce was issued here and is consumed exactly once; the party
matches what the nonce was issued for; and the timestamps are fresh.

## Run it

```sh
npm install
npm start          # listens on :8088 (PORT), domain localhost:8088
npm test           # node --test — golden vector, build/parse, verify (Ed25519 + P-256), tamper/domain/expiry/replay
npm run typecheck  # tsc --noEmit
```

Requires Node ≥ 22 (it runs the TypeScript sources directly via Node's native
type stripping — no build step). Configuration is environment-driven:

| Var | Default | Meaning |
|---|---|---|
| `PORT` | `8088` | HTTP port |
| `DAPP_DOMAIN` | `localhost:$PORT` | the dApp identity the sign-in message names and the verifier requires |
| `DAPP_URI` | `http://$DAPP_DOMAIN/login` | the resource a sign-in authenticates for |
| `DAPP_NETWORK_ID` | `canton:localnet` | CAIP-2 network id |
| `SIWC_NONCE_TTL_SECONDS` | `300` | how long a challenge stays valid |
| `SIWC_CLOCK_SKEW_SECONDS` | `60` | tolerance on `Issued At` / `Expiration Time` |

## Status and what's next

- **Sign-in — done and tested**, including a live HTTP round-trip for both key
  algorithms.
- **Authoritative party→key binding — a known limitation.** The public key is
  currently the one the wallet claimed at connect time. Binding it to the
  party's *on-ledger* key is the job of the ledger slice below; until then the
  server trusts the connecting dApp's claim of the key.
- **Ledger watching / settlement — next slice.** Read holdings and watch for
  incoming transfers over `@canton-network/wallet-sdk` (`token` + `events`
  namespaces) against LocalNet, so the server can also verify a party's key
  on-ledger and settle merchant-style flows.
- **The wallet↔server transport — a separate step.** How the wallet actually
  reaches this server to sign (same-device deep link vs WalletConnect for the
  public case) is decided and built on its own; nothing here assumes a
  particular transport.

## License

Apache-2.0 — see the repo [LICENSE](../LICENSE) and [NOTICE](../NOTICE).
