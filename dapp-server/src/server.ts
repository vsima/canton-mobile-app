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
import QRCode from 'qrcode';
import { randomUUID } from 'node:crypto';
import { OrderBook } from './orders.ts';
import { CATALOG, checkoutCart, type CartItem } from './shop.ts';
import { checkoutUri, checkoutView } from './checkout.ts';
import { storefrontHtml } from './storefront.ts';

/** RFC 3339 UTC with no sub-second component, as the sign-in template wants. */
function rfc3339(date: Date): string {
  return date.toISOString().replace(/\.\d+Z$/, 'Z');
}

/** A compact item summary for the QR payload, capped so a big cart doesn't
 *  bloat the QR — the page and the /checkout fetch carry the full list. */
function itemSummary(lineItems: Array<{ quantity: number; name: string }>): string {
  const parts = lineItems.map((li) => `${li.quantity}× ${li.name}`);
  return parts.length <= 3 ? parts.join(' · ') : `${parts.slice(0, 2).join(' · ')} +${parts.length - 2} more`;
}

export function createApp(
  config: Config = loadConfig(),
  nonces = new NonceStore(config.nonceTtlSeconds),
  orders = new OrderBook(),
) {
  const app = express();
  app.use(express.json());

  // WalletConnect sign-in state. The client (@walletconnect/sign-client) is
  // loaded lazily — a dynamic import on first use, like the ledger watcher — so
  // a deployment that never signs in over WalletConnect never loads it, and no
  // WC_PROJECT_ID is required. One connector is reused across sign-ins.
  type WcSignIn = { status: 'pending' | 'signed-in' | 'failed'; party?: string; reason?: string; startedAt: number };
  const wcSignIns = new Map<string, WcSignIn>();
  let dappConnectorPromise: Promise<import('./wc/dapp.ts').DappConnector> | null = null;

  function wcProjectConfig(): { projectId: string; relayUrl?: string } | null {
    const projectId = process.env['WC_PROJECT_ID'];
    if (projectId === undefined || projectId === '') return null;
    const relayUrl = process.env['WC_RELAY_URL'];
    return { projectId, ...(relayUrl !== undefined && relayUrl !== '' ? { relayUrl } : {}) };
  }

  async function dappConnector(wc: { projectId: string; relayUrl?: string }) {
    if (dappConnectorPromise === null) {
      const { DappConnector } = await import('./wc/dapp.ts');
      dappConnectorPromise = DappConnector.create(wc, config.networkId, {
        name: config.shopName,
        description: `Sign in to ${config.shopName}`,
        url: config.publicUrl,
        icons: [],
      });
    }
    return dappConnectorPromise;
  }

  // The storefront — the dApp's own frontend, served by its backend.
  app.get('/', (_req: Request, res: Response) => {
    res.type('html').send(
      storefrontHtml({ shop: config.shopName, merchantParty: config.merchantParty, networkId: config.networkId }),
    );
  });

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

  // --- Sign in with Canton over WalletConnect (a live session) -------------

  // Start a WalletConnect sign-in: open a session, return its pairing `uri`
  // (and a QR) at once. The CIP-0103 round-trip — connect → listAccounts →
  // signMessage → verify — completes in the background when a wallet pairs and
  // signs; the page polls /siwc-wc/status/:id for the result.
  app.post('/siwc-wc/start', async (_req: Request, res: Response) => {
    const wc = wcProjectConfig();
    if (wc === null) {
      return res.status(501).json({ error: 'WalletConnect sign-in is not configured — set WC_PROJECT_ID' });
    }
    try {
      const dapp = await dappConnector(wc);
      const { beginWalletConnectSignIn } = await import('./wc/signin.ts');
      const { uri, done } = await beginWalletConnectSignIn(dapp, {
        domain: config.domain,
        loginUri: config.uri,
        networkId: config.networkId,
        shopName: config.shopName,
      });
      const id = randomUUID();
      wcSignIns.set(id, { status: 'pending', startedAt: Date.now() });
      void done.then(
        ({ party }) => wcSignIns.set(id, { status: 'signed-in', party, startedAt: Date.now() }),
        (e: unknown) => wcSignIns.set(id, { status: 'failed', reason: (e as Error).message, startedAt: Date.now() }),
      );
      const qrSvg = await QRCode.toString(uri, { type: 'svg', margin: 1 });
      res.status(201).json({ id, uri, qrSvg });
    } catch (e) {
      res.status(500).json({ error: (e as Error).message });
    }
  });

  app.get('/siwc-wc/status/:id', (req: Request, res: Response) => {
    const id = req.params['id'];
    const state = typeof id === 'string' ? wcSignIns.get(id) : undefined;
    if (state === undefined) return res.status(404).json({ error: 'no such sign-in' });
    res.json({ status: state.status, party: state.party ?? null, reason: state.reason ?? null });
  });

  // --- Storefront (a shop over the order book) -----------------------------

  app.get('/shop', (_req: Request, res: Response) => {
    res.json({ products: CATALOG });
  });

  // Check out a cart: creates one payable order priced at the summed total, to
  // the merchant party, referenced by the order id in its memo. Returns the
  // line items, total, and a checkout QR the wallet scans to fetch and review.
  app.post('/shop/checkout', async (req: Request, res: Response) => {
    const body = (req.body ?? {}) as Record<string, unknown>;
    const rawItems = body['items'];
    if (!Array.isArray(rawItems) || rawItems.length === 0) {
      return res.status(400).json({ error: 'items (non-empty array of { productId, quantity }) is required' });
    }
    if (config.merchantParty === undefined) {
      return res.status(409).json({ error: 'no merchant party configured — set MERCHANT_PARTY' });
    }
    const items: CartItem[] = rawItems.map((it) => {
      const o = (it ?? {}) as Record<string, unknown>;
      return { productId: String(o['productId'] ?? ''), quantity: Number(o['quantity'] ?? 0) };
    });
    try {
      const { order, lineItems, total } = checkoutCart(items, config.merchantParty, orders);
      const uri = checkoutUri({
        publicUrl: config.publicUrl,
        orderId: order.id,
        payTo: order.payTo,
        amount: order.amount,
        instrument: order.instrumentId ?? 'Amulet',
        memo: order.memo,
        shop: config.shopName,
        item: itemSummary(lineItems),
      });
      const qrSvg = await QRCode.toString(uri, { type: 'svg', margin: 1 });
      res.status(201).json({
        order,
        lineItems,
        total,
        payment: { payTo: order.payTo, amount: order.amount, instrumentId: order.instrumentId ?? null, memo: order.memo },
        checkout: { uri, qrSvg },
      });
    } catch (e) {
      res.status(400).json({ error: (e as Error).message });
    }
  });

  // The reproduced checkout a wallet fetches after scanning the QR: what's
  // being bought, for how much, to whom, and referencing which memo.
  app.get('/checkout/:id', (req: Request, res: Response) => {
    const id = req.params['id'];
    const order = typeof id === 'string' ? orders.get(id) : undefined;
    if (order === undefined) return res.status(404).json({ error: 'no such checkout' });
    res.json(checkoutView(order, config.shopName));
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
      // Advance the cursor forward only: token.holdings returns nextOffset 0
      // when nothing is new, which must not reset the watch back to genesis.
      let advanced = cursor;
      for (const payment of payments) {
        advanced = Math.max(advanced, payment.offset);
        const settled = orders.settleFrom(payment);
        if (settled !== null) {
          log(
            `settled order ${settled.id} — ${payment.amount} ${payment.instrument.id} ` +
              `from ${payment.sender.slice(0, 24)}… (update ${payment.updateId.slice(0, 12)}…)`,
          );
        }
      }
      cursor = Math.max(advanced, nextOffset);
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
