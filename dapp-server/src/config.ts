// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

// Server configuration, environment-driven with LocalNet-friendly defaults.
// The domain is the dApp's identity: it appears on line 1 of every sign-in
// message and is what the verifier requires the signed message to name, so a
// signature captured for one dApp cannot be replayed against another.

export interface Config {
  /** TCP port the HTTP server binds. */
  port: number;
  /** The dApp's identity — SIWC line 1, and the verifier's expected domain. */
  domain: string;
  /** The resource a sign-in authenticates for (SIWC `URI:`). */
  uri: string;
  /** CAIP-2 network id (SIWC `Network:`). */
  networkId: string;
  /** How long an issued nonce stays valid. */
  nonceTtlSeconds: number;
  /** Clock tolerance when checking `Issued At` / `Expiration Time`. */
  clockSkewSeconds: number;

  /** The shop's display name, shown when a wallet reviews a checkout. */
  shopName: string;
  /** The base URL a wallet can reach this server at — baked into the checkout
   *  QR. Must be reachable from the phone (a LAN address, not localhost, for a
   *  real device). */
  publicUrl: string;

  /** The merchant party the ledger watcher settles orders against. When unset,
   *  orders still work but nothing auto-settles (no party to watch). */
  merchantParty?: string;
  /** The authoritative admin of the instrument to accept (the DSO party, for
   *  `Amulet`). When set, orders pin `(admin, id)` so a look-alike token minted
   *  under another admin can't settle them. When unset, only the id is checked
   *  — fine for a closed LocalNet demo, unsafe against untrusted issuers. */
  instrumentAdmin?: string;
  /** How often the watcher polls for new payments. */
  watchIntervalMs: number;
  /** JSON Ledger API of the merchant party's participant. */
  ledgerClientUrl: string;
  /** Token registry base URL. */
  registryUrl: string;
  /** Validator API base URL. */
  validatorUrl: string;
  /** Ledger API user the dev token authenticates as. */
  ledgerUserId: string;
}

function str(name: string, fallback: string): string {
  const v = process.env[name];
  return v !== undefined && v !== '' ? v : fallback;
}

function int(name: string, fallback: number): number {
  const v = process.env[name];
  if (v === undefined || v === '') return fallback;
  const n = Number.parseInt(v, 10);
  if (Number.isNaN(n)) throw new Error(`${name} must be an integer, got ${JSON.stringify(v)}`);
  return n;
}

export function loadConfig(): Config {
  const port = int('PORT', 8088);
  const domain = str('DAPP_DOMAIN', `localhost:${port}`);
  const merchantParty = process.env['MERCHANT_PARTY'];
  const instrumentAdmin = process.env['INSTRUMENT_ADMIN'];
  return {
    port,
    domain,
    uri: str('DAPP_URI', `http://${domain}/login`),
    networkId: str('DAPP_NETWORK_ID', 'canton:localnet'),
    nonceTtlSeconds: int('SIWC_NONCE_TTL_SECONDS', 300),
    clockSkewSeconds: int('SIWC_CLOCK_SKEW_SECONDS', 60),
    shopName: str('SHOP_NAME', 'Canton Corner'),
    publicUrl: str('PUBLIC_URL', `http://localhost:${port}`),
    ...(merchantParty !== undefined && merchantParty !== '' ? { merchantParty } : {}),
    ...(instrumentAdmin !== undefined && instrumentAdmin !== '' ? { instrumentAdmin } : {}),
    watchIntervalMs: int('WATCH_INTERVAL_MS', 4000),
    // LocalNet defaults (the SDK's localNetStaticConfig values).
    ledgerClientUrl: str('LEDGER_URL', 'http://localhost:2975/'),
    registryUrl: str('REGISTRY_URL', 'http://localhost:2000/api/validator/v0/scan-proxy'),
    validatorUrl: str('VALIDATOR_URL', 'http://localhost:2000/api/validator'),
    ledgerUserId: str('LEDGER_USER_ID', 'ledger-api-user'),
  };
}
