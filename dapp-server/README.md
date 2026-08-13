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

### Sign in with Canton (spec §7.2)

A dApp proves a user controls a Canton party by having the wallet sign a
structured challenge, which this server verifies against the party's public
key. It is a convention over CIP-0103 `signMessage`, modelled on Sign-In with
Ethereum (ERC-4361), and the first *external* consumer of the SDK's
`DappSignMessage` domain-separation scheme — the same 38-byte prefix the Kotlin
and Swift wallets sign, verified here byte-for-byte against the
[shared golden vector](../../canton-mobile-sdk/testdata/dapp/signmessage.json).

| Method | Path | Body | Result |
|---|---|---|---|
| `GET`  | `/health` | — | service, domain, network, live nonces, merchant party, open orders |
| `POST` | `/siwc/challenge` | `{ party, publicKey, uri?, statement? }` | `{ message, nonce, expiresAt }` |
| `POST` | `/siwc/verify` | `{ message, signature }` | `200 { ok, party }` or `401 { ok:false, reason }` |

`publicKey` is the party's SPKI public key (DER, hex) — exactly what the
wallet's `listAccounts` publishes. It is bound to the nonce at challenge time,
so `/siwc/verify` checks the signature against that key rather than trusting one
from the verify request. `signature` is hex.

### Storefront — a shop, with scan-to-pay

A browsable shop, the dApp's own frontend served by its backend. Buy a product
and the checkout screen shows a **QR the wallet scans to fetch and review the
order**, then pay; the page polls until the ledger watcher settles it and flips
to **Paid ✓**. This is the demo: open it, buy a coffee, scan with a Canton
wallet, pay, watch it settle.

| Method | Path | Result |
|---|---|---|
| `GET`  | `/` | the storefront page (self-contained HTML) |
| `GET`  | `/shop` | `{ products }` — the catalog |
| `POST` | `/shop/checkout` | `{ productId }` → `201 { product, order, payment, checkout }` |
| `GET`  | `/checkout/:id` | the reproduced checkout a wallet fetches for review |

**The order-fetch flow.** Checkout returns `checkout.qrSvg`, a QR encoding
`canton-checkout:<PUBLIC_URL>/checkout/<id>`. The wallet scans it, `GET`s that
URL for the reproduced order (shop, item, amount, party, memo), shows it for
review, and pays. The web confirms by watching the ledger. Canton has no
payment-URI standard (the CIPs define none), so `canton-checkout:` is a small
convention of this reference — the scheme only marks the QR; the URL is where
the wallet reads the order. The customer's wallet still approves and sends, so
an untrusted QR can mislead a prefill but cannot move funds.

Checkout creates an order priced in Canton Coin, payable to `MERCHANT_PARTY`.
Set that party (and point at a running LocalNet) for the shop to be payable
and to auto-settle; the party should accept incoming transfers directly
(instant receiving / preapproval) so a payment settles without a manual accept.
For a phone to reach the QR's URL, set `PUBLIC_URL` to a LAN address, not
`localhost`.

### Merchant orders — ledger watch and settle

A dApp records the payment it expects; the server watches the ledger for the
matching transfer to land and settles the order. The **customer's wallet moves
the funds** — the server only watches and confirms, which is the realistic
split and needs no key on the server. Built on `@canton-network/wallet-sdk`,
polling `token.holdings` for the merchant party's incoming `TransferIn` events.

| Method | Path | Body | Result |
|---|---|---|---|
| `POST` | `/orders` | `{ amount, payTo?, instrumentId?, memo? }` | `201 { order, payment }` |
| `GET`  | `/orders` | — | `{ orders }` |
| `GET`  | `/orders/:id` | — | `{ order }` or `404` |

`payTo` defaults to the configured `MERCHANT_PARTY`. The response's `payment`
block is what the payer must send — `amount` to `payTo` with `memo` (the order
id by default) as the transfer memo. An order settles when an incoming payment
to `payTo` carries that memo and an equal-or-greater amount (and the named
instrument, if any). Settlement requires `MERCHANT_PARTY` to be set so the
watcher knows which party to poll; without it, orders are created but never
auto-settle.

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
MERCHANT_PARTY=<party> npm start   # then open http://localhost:8088 for the shop
npm run demo                       # headless: a simulated customer buys and pays a cart
npm test                           # node --test — sign-in, order matching, and the shop
npm run typecheck                  # tsc --noEmit
```

`npm run demo` runs the whole loop with no phone, against a running server and
LocalNet: it allocates a fresh customer party, funds it, checks out a cart, pays
the order, and waits for the shop to settle it — the SDK's external-party
pipeline end to end.

Requires Node ≥ 22 (it runs the TypeScript sources directly via Node's native
type stripping — no build step). Point it at a running Splice LocalNet (the
SDK's `integration/run-localnet.sh`) for settlement. Configuration is
environment-driven:

| Var | Default | Meaning |
|---|---|---|
| `PORT` | `8088` | HTTP port |
| `DAPP_DOMAIN` | `localhost:$PORT` | the dApp identity the sign-in message names and the verifier requires |
| `DAPP_URI` | `http://$DAPP_DOMAIN/login` | the resource a sign-in authenticates for |
| `DAPP_NETWORK_ID` | `canton:localnet` | CAIP-2 network id |
| `SIWC_NONCE_TTL_SECONDS` | `300` | how long a challenge stays valid |
| `SIWC_CLOCK_SKEW_SECONDS` | `60` | tolerance on `Issued At` / `Expiration Time` |
| `SHOP_NAME` | `Canton Corner` | shop name shown on the page and when a wallet reviews a checkout |
| `PUBLIC_URL` | `http://localhost:$PORT` | base URL baked into the checkout QR; use a LAN address for a real phone |
| `MERCHANT_PARTY` | — | the party the watcher settles orders against; unset = no auto-settle |
| `WATCH_INTERVAL_MS` | `4000` | how often the watcher polls for new payments |
| `LEDGER_URL` | `http://localhost:2975/` | JSON Ledger API of the merchant party's participant |
| `REGISTRY_URL` | `…:2000/api/validator/v0/scan-proxy` | token registry |
| `VALIDATOR_URL` | `http://localhost:2000/api/validator` | validator API |
| `LEDGER_USER_ID` | `ledger-api-user` | user the dev token authenticates as |

## Status and what's next

- **Sign-in — done and tested**, including a live HTTP round-trip for both key
  algorithms.
- **Ledger watch and settle — done and live-verified.** The full read → parse →
  match → settle pipeline is exercised against a running LocalNet on real
  on-ledger payments (real sender, amount, and memo), and the "watch from now"
  cursor is confirmed not to replay historical payments. Order matching is unit
  tested.
- **Storefront + scan-to-pay — done.** Catalog, checkout, a self-contained page,
  and the order-fetch QR flow: the wallet scans, fetches `/checkout/:id`, reviews
  the order, and pays; the page polls to Paid. The wallet side (scan → fetch →
  review → prefill Send) is built on both iOS and Android. Server verified live;
  the wallet fetch is a plain `GET` of the checkout JSON.
- **Headless demo (`npm run demo`) — done.** A simulated customer runs the whole
  loop with no phone: allocate a fresh party, fund it, check out a cart, pay the
  order, and wait for the shop to settle it. This is the "fresh send → settle"
  step, the SDK's external-party pipeline end to end (`keys.generate` →
  `party.external` allocate → `amulet.tap` → `token.transfer.create`, each
  `ledger.prepare` → `sign` → `execute`). Live-verified against LocalNet.
- **Authoritative party→key binding — still open.** Sign-in currently trusts the
  public key the wallet claimed at connect time; binding it to the party's
  on-ledger key is a focused follow-up now that the ledger connection exists.
- **The wallet↔server transport — a separate step.** How the wallet reaches this
  server to sign (same-device deep link vs WalletConnect for the public case) is
  decided and built on its own; nothing here assumes a particular transport.

## License

Apache-2.0 — see the repo [LICENSE](../LICENSE) and [NOTICE](../NOTICE).
