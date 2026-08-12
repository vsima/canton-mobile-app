// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

// The reference dApp server: a public HTTP backend a wallet reaches from the
// outside. This is the shape a real dApp takes — a service on the internet,
// not a listener on a phone. Today it hosts the Sign-in-with-Canton flow
// (§7.2); ledger watching / settlement over the official wallet-sdk is the
// next slice.

import express, { type Request, type Response } from 'express';
import { loadConfig } from './config.ts';
import { NonceStore } from './nonceStore.ts';
import { buildSignInMessage, verifySignIn } from './siwc.ts';

/** RFC 3339 UTC with no sub-second component, as the sign-in template wants. */
function rfc3339(date: Date): string {
  return date.toISOString().replace(/\.\d+Z$/, 'Z');
}

export function createApp(config = loadConfig(), nonces = new NonceStore(config.nonceTtlSeconds)) {
  const app = express();
  app.use(express.json());

  app.get('/health', (_req: Request, res: Response) => {
    res.json({
      ok: true,
      service: 'canton-dapp-server',
      domain: config.domain,
      networkId: config.networkId,
      liveNonces: nonces.size,
    });
  });

  // Issue a sign-in challenge. In the full flow a dApp first connects to the
  // wallet and reads the account (party + publicKey) via listAccounts, then
  // asks here for a challenge to have the wallet sign. The public key is bound
  // to the nonce now, so /siwc/verify checks the signature against exactly it.
  app.post('/siwc/challenge', (req: Request, res: Response) => {
    const body = (req.body ?? {}) as Record<string, unknown>;
    const { party, publicKey, uri, statement } = body;
    if (typeof party !== 'string' || party === '') {
      return res.status(400).json({ error: 'party (string) is required' });
    }
    if (typeof publicKey !== 'string' || publicKey === '') {
      return res.status(400).json({ error: 'publicKey (SPKI DER, hex) is required' });
    }

    const now = new Date();
    const { nonce, expiresAt } = nonces.issue(party, publicKey, now);
    const message = buildSignInMessage({
      domain: config.domain,
      party,
      statement: typeof statement === 'string' ? statement : undefined,
      uri: typeof uri === 'string' && uri !== '' ? uri : config.uri,
      networkId: config.networkId,
      nonce,
      issuedAt: rfc3339(now),
      expirationTime: rfc3339(expiresAt),
    });
    res.json({ message, nonce, expiresAt: rfc3339(expiresAt) });
  });

  // Verify a signed challenge. The wallet signed the message via signMessage;
  // the dApp forwards the exact message and the signature (hex). Success means
  // the caller controls the party the message names.
  app.post('/siwc/verify', (req: Request, res: Response) => {
    const body = (req.body ?? {}) as Record<string, unknown>;
    const { message, signature } = body;
    if (typeof message !== 'string' || typeof signature !== 'string') {
      return res.status(400).json({ error: 'message (string) and signature (hex) are required' });
    }

    const result = verifySignIn({
      message,
      signature: Buffer.from(signature, 'hex'),
      expectedDomain: config.domain,
      expectedNetworkId: config.networkId,
      clockSkewSeconds: config.clockSkewSeconds,
      consumeNonce: (nonce, now) => nonces.consume(nonce, now),
    });

    if (!result.ok) return res.status(401).json({ ok: false, reason: result.reason });
    res.json({ ok: true, party: result.party });
  });

  return app;
}

// Started directly (not imported by a test): bind the port and sweep expired
// nonces on a slow timer.
if (process.argv[1] !== undefined && import.meta.url === `file://${process.argv[1]}`) {
  const config = loadConfig();
  const nonces = new NonceStore(config.nonceTtlSeconds);
  const app = createApp(config, nonces);
  setInterval(() => nonces.sweep(), 60_000).unref();
  app.listen(config.port, () => {
    console.log(
      `[dapp-server] listening on :${config.port}  domain=${config.domain}  network=${config.networkId}`,
    );
  });
}
