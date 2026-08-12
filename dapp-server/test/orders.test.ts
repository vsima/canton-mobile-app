// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

import { test } from 'node:test';
import assert from 'node:assert/strict';

import { OrderBook, scaledAmount } from '../src/orders.ts';
import type { IncomingPayment } from '../src/ledger.ts';

const MERCHANT = 'merchant::1220aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';

function payment(over: Partial<IncomingPayment> = {}): IncomingPayment {
  return {
    updateId: 'update-1',
    offset: 42,
    recordTime: '2026-08-12T00:00:00Z',
    sender: 'bob::1220ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff',
    receiver: MERCHANT,
    amount: '5.0000000000',
    instrument: { admin: 'DSO::1220dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd', id: 'Amulet' },
    ...over,
  };
}

test('scaledAmount compares equal across trailing-zero spellings', () => {
  assert.equal(scaledAmount('5'), scaledAmount('5.0'));
  assert.equal(scaledAmount('5'), scaledAmount('5.0000000000'));
  assert.ok(scaledAmount('5.5') > scaledAmount('5'));
  assert.ok(scaledAmount('5') > scaledAmount('4.9999999999'));
});

test('scaledAmount rejects non-decimal input', () => {
  for (const bad of ['', 'abc', '-1', '1.2.3', '0x10', ' 5 x']) {
    assert.throws(() => scaledAmount(bad), /decimal/);
  }
});

test('a new order defaults its memo to the order id and is pending', () => {
  const book = new OrderBook();
  const order = book.create({ payTo: MERCHANT, amount: '5' });
  assert.equal(order.status, 'pending');
  assert.equal(order.memo, order.id);
  assert.equal(book.get(order.id)?.status, 'pending');
});

test('create rejects a bad amount up front', () => {
  assert.throws(() => new OrderBook().create({ payTo: MERCHANT, amount: 'lots' }), /decimal/);
});

test('a matching payment settles the order and records who paid', () => {
  const book = new OrderBook();
  const order = book.create({ payTo: MERCHANT, amount: '5' });
  const settled = book.settleFrom(payment({ memo: order.memo }));
  assert.equal(settled?.id, order.id);
  assert.equal(book.get(order.id)?.status, 'settled');
  assert.equal(book.get(order.id)?.settledBy?.updateId, 'update-1');
  assert.equal(book.get(order.id)?.settledBy?.amount, '5.0000000000');
});

test('an equal-or-greater amount settles; underpayment does not', () => {
  const book = new OrderBook();
  const exact = book.create({ payTo: MERCHANT, amount: '5' });
  assert.equal(book.settleFrom(payment({ memo: exact.memo, amount: '5' }))?.id, exact.id);

  const over = book.create({ payTo: MERCHANT, amount: '5' });
  assert.equal(book.settleFrom(payment({ memo: over.memo, amount: '7.5' }))?.id, over.id);

  const under = book.create({ payTo: MERCHANT, amount: '5' });
  assert.equal(book.settleFrom(payment({ memo: under.memo, amount: '4.5' })), null);
  assert.equal(book.get(under.id)?.status, 'pending');
});

test('the wrong party, wrong memo, or wrong instrument does not settle', () => {
  const book = new OrderBook();
  const order = book.create({ payTo: MERCHANT, amount: '5', instrumentId: 'Amulet' });

  assert.equal(book.settleFrom(payment({ memo: order.memo, receiver: 'someone-else::1220' })), null);
  assert.equal(book.settleFrom(payment({ memo: 'not-the-memo' })), null);
  assert.equal(
    book.settleFrom(payment({ memo: order.memo, instrument: { admin: 'DSO::1220dd', id: 'WrongCoin' } })),
    null,
  );
  assert.equal(book.get(order.id)?.status, 'pending');

  // The right everything settles it.
  assert.equal(book.settleFrom(payment({ memo: order.memo }))?.id, order.id);
});

test('a settled order is not settled twice', () => {
  const book = new OrderBook();
  const order = book.create({ payTo: MERCHANT, amount: '5' });
  assert.equal(book.settleFrom(payment({ memo: order.memo, updateId: 'first' }))?.id, order.id);
  // A second matching payment finds no pending order.
  assert.equal(book.settleFrom(payment({ memo: order.memo, updateId: 'second' })), null);
  assert.equal(book.get(order.id)?.settledBy?.updateId, 'first');
});

test('an explicit memo overrides the default', () => {
  const book = new OrderBook();
  const order = book.create({ payTo: MERCHANT, amount: '5', memo: 'invoice-4021' });
  assert.equal(order.memo, 'invoice-4021');
  assert.equal(book.settleFrom(payment({ memo: 'invoice-4021' }))?.id, order.id);
});
