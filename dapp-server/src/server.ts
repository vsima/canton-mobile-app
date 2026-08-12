// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

// The reference dApp server: a public HTTP backend a wallet reaches from the
// outside. This is the shape a real dApp takes — a service on the internet,
// not a listener on a phone. It hosts the Sign-in-with-Canton flow (§7.2) and
// a merchant order book that settles when the matching payment lands on the
// ledger.

import express, { type Request, type Response } from 'express';
import { loadConfig, type Config } from './config.ts';
import { NonceStore } from './nonceStore.ts';
import { buildSignInMessage, verifySignIn } from './siwc.ts';
import { OrderBook } from './orders.ts';

/** RFC 3339 UTC with no sub-second component, as the sign-in template wants. */
function rfc3339(date: Date): string {
  return date.toISOString().replace(/\.\d+Z$/, 'Z');
}

export function createApp(
  config: Config = loadConfig(),
  nonces = new NonceStore(config.nonceTtlSeconds),
  orders = new OrderBook(),
) {
  const app = express();
  app.use(express.json());

  app.get('/health', (_req: Request, res: Response) => {
    res.json({
      ok: true,
      service: 'canton-dapp-server',
      domain: config.domain,
      networkId: config.networkId,
      liveNonces: nonces.size,
      merchantParty: config.merchantParty ?? null,
      openOrders: orders.list().filter((o) => o.status === 'pending').length,
    });
  });

  // --- Sign in with Canton (spec §7.2) -------------------------------------

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

  // --- Merchant orders (settled by the ledger watcher) ---------------------

  // Record an expected payment. `payTo` defaults to the configured merchant
  // party. The response tells the payer exactly what to send — amount, to whom,
  // and the memo to reference (the order id) — which the watcher then matches.
  app.post('/orders', (req: Request, res: Response) => {
    const body = (req.body ?? {}) as Record<string, unknown>;
    const payTo = typeof body['payTo'] === 'string' && body['payTo'] !== '' ? body['payTo'] : config.merchantParty;
    const { amount, instrumentId, memo } = body;
    if (typeof payTo !== 'string' || payTo === '') {
      return res.status(400).json({ error: 'payTo is required (no merchant party configured)' });
    }
    if (typeof amount !== 'string' || amount === '') {
      return res.status(400).json({ error: 'amount (decimal string) is required' });
    }
    try {
      const order = orders.create({
        payTo,
        amount,
        ...(typeof instrumentId === 'string' ? { instrumentId } : {}),
        ...(typeof memo === 'string' ? { memo } : {}),
      });
      res.status(201).json({
        order,
        // What the payer must do for this order to settle.
        payment: { payTo: order.payTo, amount: order.amount, instrumentId: order.instrumentId ?? null, memo: order.memo },
      });
    } catch (e) {
      res.status(400).json({ error: (e as Error).message });
    }
  });

  app.get('/orders', (_req: Request, res: Response) => {
    res.json({ orders: orders.list() });
  });

  app.get('/orders/:id', (req: Request, res: Response) => {
    const id = req.params['id'];
    const order = typeof id === 'string' ? orders.get(id) : undefined;
    if (order === undefined) return res.status(404).json({ error: 'no such order' });
    res.json({ order });
  });

  return app;
}

/**
 * Starts the ledger watcher: polls the merchant party for new incoming
 * payments and settles matching orders. The SDK is imported dynamically so a
 * sign-in-only deployment (no LocalNet) never loads it. Returns a stop
 * function. No-op — and returns null — when no merchant party is configured.
 */
export async function startWatcher(
  config: Config,
  orders: OrderBook,
  log: (message: string) => void = () => {},
): Promise<(() => void) | null> {
  if (config.merchantParty === undefined) return null;
  const party = config.merchantParty;
  const { Ledger } = await import('./ledger.ts');
  const ledger = await Ledger.connect({
    ledgerClientUrl: config.ledgerClientUrl,
    registryUrl: config.registryUrl,
    validatorUrl: config.validatorUrl,
    authUserId: config.ledgerUserId,
    authSecret: 'unsafe',
    audience: 'https://canton.network.global',
  });

  // Watch from now — only payments that arrive after startup settle orders.
  let cursor = await ledger.ledgerEnd();
  let stopped = false;

  const poll = async (): Promise<void> => {
    if (stopped) return;
    try {
      const { payments, nextOffset } = await ledger.incomingPayments(party, cursor);
      for (const payment of payments) {
        const settled = orders.settleFrom(payment);
        if (settled !== null) {
          log(
            `settled order ${settled.id} — ${payment.amount} ${payment.instrument.id} ` +
              `from ${payment.sender.slice(0, 24)}… (update ${payment.updateId.slice(0, 12)}…)`,
          );
        }
      }
      cursor = nextOffset;
    } catch (e) {
      log(`watch poll failed: ${(e as Error).message}`);
    }
  };

  await poll();
  const timer = setInterval(() => void poll(), config.watchIntervalMs);
  timer.unref();
  log(`watching ${party.slice(0, 28)}… from offset ${cursor}`);
  return () => {
    stopped = true;
    clearInterval(timer);
  };
}

// Started directly (not imported by a test): bind the port, start the watcher
// if a merchant party is configured, and sweep expired nonces on a slow timer.
if (process.argv[1] !== undefined && import.meta.url === `file://${process.argv[1]}`) {
  const config = loadConfig();
  const nonces = new NonceStore(config.nonceTtlSeconds);
  const orders = new OrderBook();
  const app = createApp(config, nonces, orders);
  setInterval(() => nonces.sweep(), 60_000).unref();
  app.listen(config.port, () => {
    console.log(
      `[dapp-server] listening on :${config.port}  domain=${config.domain}  network=${config.networkId}`,
    );
  });
  startWatcher(config, orders, (m) => console.log(`[watcher] ${m}`)).catch((e) =>
    console.error(`[watcher] failed to start: ${(e as Error).message}`),
  );
}
