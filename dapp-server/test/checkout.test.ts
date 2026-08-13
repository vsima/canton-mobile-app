// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import { test } from 'node:test';
import assert from 'node:assert/strict';

import { checkoutUri, checkoutView, CHECKOUT_SCHEME } from '../src/checkout.ts';
import { OrderBook } from '../src/orders.ts';
import { checkout } from '../src/shop.ts';

test('checkoutUri marks the QR with the scheme and the fetch URL', () => {
  assert.equal(checkoutUri('http://localhost:8088', 'abc'), 'canton-checkout:http://localhost:8088/checkout/abc');
  // A trailing slash on the base does not double up.
  assert.equal(checkoutUri('http://host:8088/', 'abc'), 'canton-checkout:http://host:8088/checkout/abc');
  assert.ok(checkoutUri('http://x', 'y').startsWith(CHECKOUT_SCHEME));
});

test('checkoutView reproduces the order for the wallet to review', () => {
  const orders = new OrderBook();
  const { order } = checkout('coffee', 'merchant::1220aa', orders);
  const view = checkoutView(order, 'Canton Corner');
  assert.equal(view.shop, 'Canton Corner');
  assert.equal(view.item, '☕ Coffee'); // the product description set at checkout
  assert.equal(view.amount, '5');
  assert.equal(view.instrumentId, 'Amulet');
  assert.equal(view.payTo, 'merchant::1220aa');
  assert.equal(view.memo, order.id);
  assert.equal(view.status, 'pending');
});
