// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import { test } from 'node:test';
import assert from 'node:assert/strict';

import { CATALOG, findProduct, checkout, INSTRUMENT_ID } from '../src/shop.ts';
import { OrderBook } from '../src/orders.ts';
import type { IncomingPayment } from '../src/ledger.ts';

const MERCHANT = 'merchant::1220aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';

test('the catalog has products, each priced and identifiable', () => {
  assert.ok(CATALOG.length > 0);
  for (const p of CATALOG) {
    assert.ok(p.id && p.name && p.emoji);
    assert.doesNotThrow(() => BigInt(p.priceCc.replace('.', ''))); // a plausible decimal price
    assert.equal(findProduct(p.id)?.id, p.id);
  }
});

test('checkout creates a pending Amulet order priced at the product', () => {
  const orders = new OrderBook();
  const { product, order } = checkout('coffee', MERCHANT, orders);
  assert.equal(product.id, 'coffee');
  assert.equal(order.status, 'pending');
  assert.equal(order.payTo, MERCHANT);
  assert.equal(order.amount, product.priceCc);
  assert.equal(order.instrumentId, INSTRUMENT_ID);
  assert.equal(order.memo, order.id); // the payer references the order id
});

test('an unknown product is rejected', () => {
  assert.throws(() => checkout('spaceship', MERCHANT, new OrderBook()), /no such product/);
});

test('paying the checked-out order settles it (the full shop loop, in-memory)', () => {
  const orders = new OrderBook();
  const { product, order } = checkout('mug', MERCHANT, orders);
  const payment: IncomingPayment = {
    updateId: 'u-shop',
    offset: 1,
    recordTime: '2026-08-12T00:00:00Z',
    sender: 'buyer::1220ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff',
    receiver: MERCHANT,
    amount: product.priceCc,
    instrument: { admin: 'DSO::1220dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd', id: INSTRUMENT_ID },
    memo: order.memo,
  };
  assert.equal(orders.settleFrom(payment)?.id, order.id);
  assert.equal(orders.get(order.id)?.status, 'settled');
});
