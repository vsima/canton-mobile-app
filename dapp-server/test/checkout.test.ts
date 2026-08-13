// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import { test } from 'node:test';
import assert from 'node:assert/strict';

import { checkoutUri, checkoutView, CHECKOUT_SCHEME } from '../src/checkout.ts';
import { OrderBook } from '../src/orders.ts';
import { checkoutCart } from '../src/shop.ts';

test('checkoutUri builds a self-describing canton-checkout://pay payload', () => {
  const uri = checkoutUri({
    publicUrl: 'http://host:8088/', // trailing slash
    orderId: 'abc',
    payTo: 'merchant::1220aa',
    amount: '12',
    instrument: 'Amulet',
    memo: 'abc',
    shop: 'Canton Corner',
    item: '2× Coffee · 1× Stickers',
  });
  assert.ok(uri.startsWith(`${CHECKOUT_SCHEME}://pay?`));
  // Parse it back the way the wallets do — decoded query params.
  const params = new URL(uri).searchParams;
  assert.equal(params.get('to'), 'merchant::1220aa');
  assert.equal(params.get('amount'), '12');
  assert.equal(params.get('instrument'), 'Amulet');
  assert.equal(params.get('memo'), 'abc');
  assert.equal(params.get('shop'), 'Canton Corner');
  assert.equal(params.get('item'), '2× Coffee · 1× Stickers');
  assert.equal(params.get('url'), 'http://host:8088/checkout/abc'); // trailing slash trimmed
});

test('checkoutView reproduces the order for the wallet to review', () => {
  const orders = new OrderBook();
  const { order } = checkoutCart([{ productId: 'coffee', quantity: 1 }], 'merchant::1220aa', orders);
  const view = checkoutView(order, 'Canton Corner');
  assert.equal(view.shop, 'Canton Corner');
  assert.equal(view.item, '1× Coffee'); // the item summary set at checkout
  assert.equal(view.amount, '5');
  assert.equal(view.instrumentId, 'Amulet');
  assert.equal(view.payTo, 'merchant::1220aa');
  assert.equal(view.memo, order.id);
  assert.equal(view.status, 'pending');
});
