// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

// The WalletConnect ⇄ Canton contract. WalletConnect is not a new capability —
// it is a live-session *transport* for the CIP-0103 requests the wallet already
// answers (the same `signMessage` and transfer the scan-to-pay QR carries as a
// one-shot payload). This module is the transport-agnostic half: the namespace,
// the method names, the CAIP identifiers, and the request/result shapes. It
// imports neither the WalletConnect SDK nor the Canton SDK, so both ends — and
// the tests — share one definition of what crosses the wire.

/** WalletConnect namespace for Canton. WalletConnect groups a session's chains,
 *  methods and accounts under a namespace key (`eip155` for Ethereum); Canton
 *  has no registered one, so this reference defines `canton`. */
export const CANTON_NAMESPACE = 'canton';

/** The CIP-0103 methods this reference carries over a session, as JSON-RPC
 *  method names. `session_request({ method, params })` routes to one of these. */
export const CANTON_METHODS = {
  /** Sign a structured message in the wallet (CIP-0103 `signMessage`); the
   *  basis for Sign-In with Canton. Params {@link SignMessageParams}. */
  signMessage: 'canton_signMessage',
  /** Ask the wallet to approve and submit a token transfer. Params
   *  {@link RequestTransferParams}. The wallet holds the keys and pays. */
  requestTransfer: 'canton_requestTransfer',
} as const;

export type CantonMethod = (typeof CANTON_METHODS)[keyof typeof CANTON_METHODS];

/** Every method this responder implements — the `methods` a session advertises. */
export const ALL_METHODS: readonly string[] = Object.values(CANTON_METHODS);

// --- CAIP identifiers -------------------------------------------------------
//
// WalletConnect speaks CAIP: a chain is `namespace:reference` (CAIP-2) and an
// account is `namespace:reference:address` (CAIP-10). Our network id is already
// CAIP-2 shaped (`canton:localnet`), so it *is* the chain id. The catch is the
// account address: a Canton party id (`hint::1220<fingerprint>`) contains `::`,
// which the CAIP-10 address segment forbids — so it must be percent-encoded.

const CAIP2 = /^[-a-z0-9]{3,8}:[-_a-zA-Z0-9]{1,32}$/;

/** Validates a CAIP-2 chain id and returns it. Our `networkId` config value is
 *  already CAIP-2 (`canton:localnet`), so it doubles as the chain id; this just
 *  guards a mis-typed override before it reaches the relay. */
export function chainId(networkId: string): string {
  if (!CAIP2.test(networkId)) {
    throw new Error(`networkId ${JSON.stringify(networkId)} is not a CAIP-2 chain id (namespace:reference)`);
  }
  return networkId;
}

const CAIP10_ADDRESS_SAFE = /[A-Za-z0-9.\-]/;

/**
 * Encodes a Canton party id into a CAIP-10 address segment. The CAIP-10 address
 * charset is `[-.%a-zA-Z0-9]`, so every other byte — notably the `:` in `::`
 * and any `_` in a party hint — is percent-encoded (upper-case, UTF-8 bytes).
 * The inverse is a plain `decodeURIComponent`.
 */
export function encodePartyAddress(party: string): string {
  return [...new TextEncoder().encode(party)]
    .map((b) => (CAIP10_ADDRESS_SAFE.test(String.fromCharCode(b)) ? String.fromCharCode(b) : `%${b.toString(16).toUpperCase().padStart(2, '0')}`))
    .join('');
}

/** Recovers a party id from a CAIP-10 address segment. */
export function decodePartyAddress(address: string): string {
  return decodeURIComponent(address);
}

/** Builds the CAIP-10 account (`chain:encodedParty`) a session advertises. */
export function toAccount(chain: string, party: string): string {
  return `${chain}:${encodePartyAddress(party)}`;
}

/** Extracts the party id from a CAIP-10 account. The address segment carries no
 *  literal `:` (they are percent-encoded), so the last `:` splits chain from
 *  address unambiguously. */
export function partyFromAccount(account: string): string {
  const cut = account.lastIndexOf(':');
  if (cut < 0) throw new Error(`not a CAIP-10 account: ${JSON.stringify(account)}`);
  return decodePartyAddress(account.slice(cut + 1));
}

// --- request / result payloads ----------------------------------------------

/** `canton_signMessage` params: the exact string the wallet signs. */
export interface SignMessageParams {
  message: string;
}

/** `canton_signMessage` result. `signature` and `publicKey` are hex; the public
 *  key is SPKI-DER (what the dApp verifies against, and what `listAccounts`
 *  publishes). `party` echoes which account signed. */
export interface SignMessageResult {
  signature: string;
  party: string;
  publicKey: string;
}

/** `canton_requestTransfer` params — the payment the dApp asks the wallet to
 *  make. `shop`/`item` are display-only, for the wallet's approval prompt; the
 *  wallet still owns the keys and decides. */
export interface RequestTransferParams {
  /** The recipient party (the merchant). */
  to: string;
  /** Decimal amount, e.g. "12". */
  amount: string;
  /** Instrument id, e.g. "Amulet". */
  instrument: string;
  /** Transfer memo the merchant's watcher matches (usually the order id). */
  memo: string;
  /** Display: the shop name shown on the approval prompt. */
  shop?: string;
  /** Display: what is being bought. */
  item?: string;
}

/** `canton_requestTransfer` result — the on-ledger update the wallet submitted. */
export interface TransferResult {
  updateId: string;
  /** The party that paid (the wallet's own account). */
  sender: string;
}

/** Builds a `canton_requestTransfer` request payload from an order's fields. */
export function transferRequest(params: RequestTransferParams): { method: string; params: RequestTransferParams } {
  return { method: CANTON_METHODS.requestTransfer, params };
}

/** Builds a `canton_signMessage` request payload. */
export function signMessageRequest(message: string): { method: string; params: SignMessageParams } {
  return { method: CANTON_METHODS.signMessage, params: { message } };
}

// --- errors -----------------------------------------------------------------
//
// CIP-0103 uses EIP-1193 / EIP-1474 error codes. A wallet that declines returns
// 4001; an unknown method is 4200; anything the wallet failed to carry out is a
// generic -32000. `WcRequestError` carries the code so the responder can put it
// straight into the JSON-RPC error response.

export const WC_ERRORS = {
  userRejected: 4001,
  unsupportedMethod: 4200,
  internal: -32000,
} as const;

export class WcRequestError extends Error {
  readonly code: number;
  constructor(code: number, message: string) {
    super(message);
    this.name = 'WcRequestError';
    this.code = code;
  }
}

