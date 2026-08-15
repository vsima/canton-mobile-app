// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import { test } from 'node:test';
import assert from 'node:assert/strict';

import { CATALOG, findProduct, checkoutCart, INSTRUMENT_ID } from '../src/shop.ts';
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

test('checkoutCart with one item prices the order at the product', () => {
  const orders = new OrderBook();
  const { order, lineItems, total } = checkoutCart([{ productId: 'coffee', quantity: 1 }], MERCHANT, orders);
  assert.equal(total, '5');
  assert.equal(order.amount, '5');
  assert.equal(order.status, 'pending');
  assert.equal(order.payTo, MERCHANT);
  assert.equal(order.instrumentId, INSTRUMENT_ID);
  assert.equal(order.memo, order.id); // the payer references the order id
  assert.equal(lineItems.length, 1);
  assert.equal(lineItems[0]?.quantity, 1);
  assert.equal(lineItems[0]?.subtotal, '5');
});

test('checkoutCart sums quantities across multiple items', () => {
  const orders = new OrderBook();
  const { order, lineItems, total } = checkoutCart(
    [{ productId: 'coffee', quantity: 2 }, { productId: 'mug', quantity: 1 }], // 2×5 + 1×8
    MERCHANT,
    orders,
  );
  assert.equal(total, '18');
  assert.equal(order.amount, '18');
  assert.equal(order.description, '2× Coffee · 1× Enamel mug');
  assert.equal(lineItems.find((l) => l.id === 'coffee')?.subtotal, '10');
  assert.equal(lineItems.find((l) => l.id === 'mug')?.subtotal, '8');
});

test('checkoutCart stamps the expected instrument admin when given one', () => {
  const orders = new OrderBook();
  const DSO = 'DSO::1220dddd';
  const withAdmin = checkoutCart([{ productId: 'coffee', quantity: 1 }], MERCHANT, orders, DSO);
  assert.equal(withAdmin.order.instrumentAdmin, DSO);

  // Omitted (or empty) leaves the order unpinned — id-only matching.
  const noAdmin = checkoutCart([{ productId: 'coffee', quantity: 1 }], MERCHANT, orders);
  assert.equal(noAdmin.order.instrumentAdmin, undefined);
});

test('an unknown product, empty cart, or bad quantity is rejected', () => {
  assert.throws(() => checkoutCart([{ productId: 'spaceship', quantity: 1 }], MERCHANT, new OrderBook()), /no such product/);
  assert.throws(() => checkoutCart([], MERCHANT, new OrderBook()), /empty/);
  assert.throws(() => checkoutCart([{ productId: 'coffee', quantity: 0 }], MERCHANT, new OrderBook()), /quantity/);
});

test('paying the checked-out cart settles it (the full shop loop, in-memory)', () => {
  const orders = new OrderBook();
  const { order, total } = checkoutCart(
    [{ productId: 'coffee', quantity: 2 }, { productId: 'stickers', quantity: 1 }], // 2×5 + 1×2 = 12
    MERCHANT,
    orders,
  );
  assert.equal(total, '12');
  const payment: IncomingPayment = {
    updateId: 'u-shop',
    offset: 1,
    recordTime: '2026-08-12T00:00:00Z',
    sender: 'buyer::1220ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff',
    receiver: MERCHANT,
    amount: total,
    instrument: { admin: 'DSO::1220dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd', id: INSTRUMENT_ID },
    memo: order.memo,
  };
  assert.equal(orders.settleFrom(payment)?.id, order.id);
  assert.equal(orders.get(order.id)?.status, 'settled');
});
