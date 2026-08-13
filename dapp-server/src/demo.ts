// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

// A headless "customer" so the whole shop loop runs with one command, no phone:
// allocate a fresh party, fund it, check out a cart on the shop, pay the order,
// and watch the shop settle it. The customer side is the SDK's external-party
// submission pipeline — allocate → tap → transfer, each prepare → sign →
// execute. Point SHOP_URL at a running dapp-server on a running LocalNet.

import { createLocalNetSdk, localnetRegistryUrl } from './localnet.ts';

const SHOP = process.env['SHOP_URL'] ?? 'http://localhost:8088';
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
function step(n: string, msg: string) { console.log(`${cyan(n)} ${msg}`); }
function note(msg: string) { console.log(`   ${msg}`); }

async function main(): Promise<void> {
  step('①', 'connecting to LocalNet…');
  const sdk = await createLocalNetSdk();

  // A fresh external customer party the demo fully controls.
  const keys = sdk.keys.generate();
  step('②', 'allocating a customer party…');
  const { partyId: customer } = await sdk.party.external.create(keys.publicKey, { partyHint: 'shopper' })
    .sign(keys.privateKey)
    .execute();
  note(`customer = ${customer.slice(0, 44)}…`);

  // prepare → sign → execute a PreparedCommand ([command, disclosedContracts]).
  const submit = async (label: string, cmd: readonly [unknown, unknown[]]): Promise<void> => {
    const [command, disclosedContracts] = cmd;
    const prepared = sdk.ledger.prepare({
      partyId: customer,
      commands: command,
      disclosedContracts: disclosedContracts as never,
    });
    const res = await prepared.sign(keys.privateKey).execute({ partyId: customer });
    note(`${label} → update ${String(res.updateId).slice(0, 12)}…`);
  };

  step('③', 'tapping test funds…');
  await submit('tap 100 CC', await sdk.amulet.tap(customer, '100'));

  step('④', `checking out a cart at ${SHOP}…`);
  const checkoutRes = await fetch(`${SHOP}/shop/checkout`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ items: CART }),
  });
  const co = (await checkoutRes.json()) as CheckoutResponse;
  if (!checkoutRes.ok) throw new Error(`checkout failed: ${JSON.stringify(co)}`);
  note(`order ${co.order.id.slice(0, 8)}… — ${co.order.description} — pay ${co.total} CC`);

  step('⑤', 'paying the order…');
  await submit('transfer', await sdk.token.transfer.create({
    sender: customer,
    recipient: co.order.payTo,
    amount: co.total,
    instrumentId: 'Amulet',
    registryUrl: localnetRegistryUrl,
    memo: co.order.memo,
  }));

  step('⑥', 'waiting for the shop to settle it…');
  for (let i = 0; i < 20; i++) {
    const o = (await (await fetch(`${SHOP}/orders/${co.order.id}`)).json()) as OrderResponse;
    if (o.order?.status === 'settled') {
      console.log(green(`\n✓ PAID — order ${co.order.id.slice(0, 8)}… settled on-ledger. The whole loop ran headless.`));
      return;
    }
    await new Promise((r) => setTimeout(r, 2000));
  }
  throw new Error('timed out waiting for the shop to settle the order');
}

main().then(() => process.exit(0)).catch((e) => {
  console.error(`\ndemo failed: ${(e as Error).message}`);
  process.exit(1);
});
