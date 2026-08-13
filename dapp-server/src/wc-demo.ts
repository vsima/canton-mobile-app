// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

// The headless WalletConnect round-trip — the live-session sibling of `demo.ts`.
//
// Where `demo.ts` has the customer call the ledger directly, here a dApp and a
// wallet meet over a real WalletConnect session (the public relay), and the
// payment is a request the dApp PUSHES — "pay this order" — that the wallet
// approves and settles. Same shop, same on-ledger settlement; the difference is
// the transport, which is the whole point: this is the one-tap flow, proven end
// to end with no phone. It authenticates the party over the session (Sign-In
// with Canton) and then pays.
//
// Needs a running shop + LocalNet (like `demo.ts`) and a WalletConnect project
// id (free, cloud.reown.com) in WC_PROJECT_ID.

import { randomUUID } from 'node:crypto';
import { createLocalNetSdk, localnetRegistryUrl } from './localnet.ts';
import { buildSignInMessage, verifySignature } from './siwc.ts';
import { DappConnector } from './wc/dapp.ts';
import { HeadlessWallet } from './wc/wallet.ts';
import { cantonWalletSigner } from './wc/signer.ts';
import { partyFromAccount } from './wc/protocol.ts';

const SHOP = process.env['SHOP_URL'] ?? 'http://localhost:8088';
const NETWORK_ID = process.env['DAPP_NETWORK_ID'] ?? 'canton:localnet';
const SHOP_NAME = process.env['SHOP_NAME'] ?? 'Canton Corner';
const DOMAIN = process.env['DAPP_DOMAIN'] ?? 'localhost:8088';
const RELAY_URL = process.env['WC_RELAY_URL'];
const PROJECT_ID = process.env['WC_PROJECT_ID'];

const CART = [
  { productId: 'coffee', quantity: 2 },
  { productId: 'stickers', quantity: 1 },
];

interface CheckoutResponse {
  order: { id: string; payTo: string; memo: string; description: string };
  total: string;
}
interface OrderResponse {
  order?: { status: string };
}

const cyan = (s: string) => `\x1b[36m${s}\x1b[0m`;
const green = (s: string) => `\x1b[32m${s}\x1b[0m`;
const dim = (s: string) => `\x1b[2m${s}\x1b[0m`;
function step(n: string, msg: string) { console.log(`${cyan(n)} ${msg}`); }
function note(msg: string) { console.log(`   ${dim(msg)}`); }

async function main(): Promise<void> {
  if (PROJECT_ID === undefined || PROJECT_ID === '') {
    throw new Error('set WC_PROJECT_ID (a free WalletConnect project id from cloud.reown.com)');
  }
  const wc = { projectId: PROJECT_ID, ...(RELAY_URL !== undefined ? { relayUrl: RELAY_URL } : {}) };

  step('①', 'connecting to LocalNet…');
  const sdk = await createLocalNetSdk();

  // The wallet's own party — a fresh external party this demo fully controls.
  const keys = sdk.keys.generate();
  step('②', 'allocating the wallet’s party…');
  const { partyId: customer } = await sdk.party.external.create(keys.publicKey, { partyHint: 'shopper' })
    .sign(keys.privateKey)
    .execute();
  note(`party = ${customer.slice(0, 44)}…`);

  step('③', 'tapping test funds…');
  const [tapCmd, tapDisclosed] = (await sdk.amulet.tap(customer, '100')) as readonly [unknown, unknown[]];
  const tapRes = await sdk.ledger
    .prepare({ partyId: customer, commands: tapCmd, disclosedContracts: tapDisclosed as never })
    .sign(keys.privateKey)
    .execute({ partyId: customer });
  note(`tapped 100 CC → update ${String(tapRes.updateId).slice(0, 12)}…`);

  // Bring both ends of the session online. The wallet is backed by the Canton
  // signer; the dApp only asks and reads replies.
  const signer = cantonWalletSigner({ sdk, keys, party: customer, registryUrl: localnetRegistryUrl, networkId: NETWORK_ID });
  const wallet = await HeadlessWallet.create(wc, signer, NETWORK_ID, {
    name: 'Canton Wallet (headless)', description: 'reference wallet', url: 'http://localhost', icons: [],
  });
  const dapp = await DappConnector.create(wc, NETWORK_ID, {
    name: SHOP_NAME, description: 'reference dApp shop', url: SHOP, icons: [],
  });

  step('④', 'opening a WalletConnect session…');
  const { uri, approved } = await dapp.createSession();
  note(`pairing uri ${uri.slice(0, 44)}…  (a phone would scan this as a QR)`);

  step('⑤', 'wallet pairs and approves…');
  await wallet.pair(uri);
  const session = await approved;
  const account = session.namespaces['canton']?.accounts[0] ?? '';
  note(`session ${session.topic.slice(0, 12)}…  account ${partyFromAccount(account).slice(0, 32)}…`);

  step('⑥', 'authenticating over the session (Sign-In with Canton)…');
  await dapp.connect(session.topic);
  const accounts = await dapp.listAccounts(session.topic);
  const acct = accounts[0];
  if (acct === undefined) throw new Error('the wallet shared no accounts');
  const message = buildSignInMessage({
    domain: DOMAIN,
    party: acct.partyId,
    statement: `Sign in to ${SHOP_NAME}.`,
    uri: `${SHOP}/login`,
    networkId: NETWORK_ID,
    nonce: randomUUID().replace(/-/g, ''),
    issuedAt: new Date().toISOString(),
  });
  const signed = await dapp.requestSignMessage(session.topic, message);
  const sigOk = verifySignature(message, acct.publicKey, Buffer.from(signed.signature, 'hex'));
  if (!sigOk) throw new Error('sign-in verification failed');
  note(`connect + listAccounts + signMessage (real CIP-0103) — verified by ${acct.partyId.slice(0, 32)}… ✓`);

  step('⑦', `checking out a cart at ${SHOP}…`);
  const checkoutRes = await fetch(`${SHOP}/shop/checkout`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ items: CART }),
  });
  const co = (await checkoutRes.json()) as CheckoutResponse;
  if (!checkoutRes.ok) throw new Error(`checkout failed: ${JSON.stringify(co)}`);
  note(`order ${co.order.id.slice(0, 8)}… — ${co.order.description} — pay ${co.total} CC`);

  step('⑧', 'requesting payment over WalletConnect…');
  const paid = await dapp.requestTransfer(session.topic, {
    to: co.order.payTo,
    amount: co.total,
    instrument: 'Amulet',
    memo: co.order.memo,
    shop: SHOP_NAME,
    item: co.order.description,
  });
  note(`wallet approved & submitted → update ${paid.updateId.slice(0, 12)}…`);

  step('⑨', 'waiting for the shop to settle it…');
  for (let i = 0; i < 20; i++) {
    const o = (await (await fetch(`${SHOP}/orders/${co.order.id}`)).json()) as OrderResponse;
    if (o.order?.status === 'settled') {
      console.log(green(`\n✓ PAID — order ${co.order.id.slice(0, 8)}… settled on-ledger, over a live WalletConnect session. No phone.`));
      await dapp.disconnect(session.topic).catch(() => {});
      return;
    }
    await new Promise((r) => setTimeout(r, 2000));
  }
  throw new Error('timed out waiting for the shop to settle the order');
}

main().then(() => process.exit(0)).catch((e) => {
  console.error(`\nwc-demo failed: ${(e as Error).message}`);
  process.exit(1);
});
