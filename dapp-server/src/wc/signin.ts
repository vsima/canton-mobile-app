// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

// Sign-In with Canton over a WalletConnect session, as a reusable step: open a
// session, hand back the pairing `uri` immediately (render it as a QR), and run
// the standard CIP-0103 round-trip — pair → connect → listAccounts → signMessage
// → verify — as `done`. Both the CLI (`wc-signin.ts`) and the storefront server
// drive it. It imports no WalletConnect client (the DappConnector is passed in,
// as a type), so it stays light.

import { randomUUID } from 'node:crypto';
import { buildSignInMessage, verifySignature } from '../siwc.ts';
import type { DappConnector } from './dapp.ts';

export interface SignInFields {
  /** The dApp identity the challenge names (SIWC line 1 / verifier domain). */
  domain: string;
  /** The resource the sign-in authenticates for (SIWC `URI:`). */
  loginUri: string;
  /** CAIP-2 network id. */
  networkId: string;
  /** Shown in the challenge statement and the wallet's approval prompt. */
  shopName: string;
}

export interface SignInOutcome {
  /** The party that signed in — verified against its published key. */
  party: string;
}

/**
 * Opens a WalletConnect session and returns its pairing `uri` at once, plus
 * `done`: the sign-in that resolves once a wallet pairs, approves, and signs.
 *
 * `done` runs the real CIP-0103 flow — `connect` (the wallet grants an account),
 * `listAccounts` (read its public key), `signMessage` (the wallet signs the
 * challenge) — and verifies the signature against that key before resolving.
 * It rejects if the wallet declines or the signature does not verify.
 */
export async function beginWalletConnectSignIn(
  dapp: DappConnector,
  fields: SignInFields,
): Promise<{ uri: string; done: Promise<SignInOutcome> }> {
  const { uri, approved } = await dapp.createSession();
  const done = (async (): Promise<SignInOutcome> => {
    const session = await approved;
    try {
      await dapp.connect(session.topic);
      const accounts = await dapp.listAccounts(session.topic);
      const account = accounts[0];
      if (account === undefined) throw new Error('the wallet shared no accounts');
      const message = buildSignInMessage({
        domain: fields.domain,
        party: account.partyId,
        statement: `Sign in to ${fields.shopName}.`,
        uri: fields.loginUri,
        networkId: fields.networkId,
        nonce: randomUUID().replace(/-/g, ''),
        issuedAt: new Date().toISOString(),
      });
      const { signature } = await dapp.requestSignMessage(session.topic, message);
      if (!verifySignature(message, account.publicKey, Buffer.from(signature, 'hex'))) {
        throw new Error('the signature did not verify against the account key');
      }
      return { party: account.partyId };
    } finally {
      await dapp.disconnect(session.topic).catch(() => {});
    }
  })();
  return { uri, done };
}
