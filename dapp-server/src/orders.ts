// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

// A minimal merchant order book: a dApp records what payment it expects, the
// ledger watcher feeds it the payments that land, and an order settles when one
// matches. In-memory, like the nonce store — the matching contract is the part
// that matters and is what a real (persisted) deployment would keep.

import { randomUUID } from 'node:crypto';
import type { IncomingPayment } from './ledger.ts';

export interface Order {
  id: string;
  /** The party the payment must go to (the merchant). */
  payTo: string;
  /** The amount expected, as a decimal string. An equal-or-greater payment settles. */
  amount: string;
  /** If set, the payment's instrument id (e.g. `Amulet`) must match. */
  instrumentId?: string;
  /**
   * If set, the payment's instrument *admin* must match too. An instrument is
   * the pair `(admin, id)`; the id alone is an issuer-chosen label anyone can
   * reuse, so `Amulet` is only authoritative under the DSO's admin party. Pin
   * this to reject a counterfeit look-alike minted under someone else's admin.
   */
  instrumentAdmin?: string;
  /** A human label for what's being bought — shown when the wallet reviews it. */
  description?: string;
  /** What the payer must reference in the transfer memo; defaults to the id. */
  memo: string;
  status: 'pending' | 'settled';
  createdAt: string;
  settledAt?: string;
  /** The payment that settled it. */
  settledBy?: { updateId: string; sender: string; amount: string };
}

export interface CreateOrder {
  payTo: string;
  amount: string;
  instrumentId?: string;
  instrumentAdmin?: string;
  description?: string;
  memo?: string;
}

/** Scales a decimal string to an integer at 10 fractional digits (token
 *  precision), so "5", "5.0" and "5.0000000000" compare equal without float
 *  rounding. Throws on anything that isn't a non-negative decimal. */
export function scaledAmount(amount: string): bigint {
  const match = /^(\d+)(?:\.(\d+))?$/.exec(amount.trim());
  if (match === null) throw new Error(`not a non-negative decimal amount: ${JSON.stringify(amount)}`);
  const whole = match[1]!;
  const frac = (match[2] ?? '').padEnd(10, '0').slice(0, 10);
  return BigInt(whole) * 10_000_000_000n + BigInt(frac);
}

/** Renders a 10-dp scaled integer back to a decimal string, trailing zeros
 *  trimmed — the inverse of {@link scaledAmount}, for summing cart totals. */
export function unscaledAmount(scaled: bigint): string {
  const whole = scaled / 10_000_000_000n;
  const frac = scaled % 10_000_000_000n;
  if (frac === 0n) return whole.toString();
  return `${whole}.${frac.toString().padStart(10, '0').replace(/0+$/, '')}`;
}

export class OrderBook {
  private readonly orders = new Map<string, Order>();

  create(input: CreateOrder, now: Date = new Date()): Order {
    // Validate the amount up front so a bad order can never sit unmatchable.
    scaledAmount(input.amount);
    const id = randomUUID();
    const order: Order = {
      id,
      payTo: input.payTo,
      amount: input.amount,
      ...(input.instrumentId !== undefined ? { instrumentId: input.instrumentId } : {}),
      ...(input.instrumentAdmin !== undefined ? { instrumentAdmin: input.instrumentAdmin } : {}),
      ...(input.description !== undefined ? { description: input.description } : {}),
      memo: input.memo !== undefined && input.memo !== '' ? input.memo : id,
      status: 'pending',
      createdAt: now.toISOString(),
    };
    this.orders.set(id, order);
    return order;
  }

  get(id: string): Order | undefined {
    return this.orders.get(id);
  }

  list(): Order[] {
    return [...this.orders.values()];
  }

  /**
   * Settles the first pending order this payment satisfies — right party, memo
   * reference, an equal-or-greater amount, and (if the order named them) the
   * right instrument id *and admin* — and returns it. Returns null if nothing
   * matched, so an unrelated payment is simply ignored.
   */
  settleFrom(payment: IncomingPayment, now: Date = new Date()): Order | null {
    for (const order of this.orders.values()) {
      if (order.status !== 'pending') continue;
      if (payment.receiver !== order.payTo) continue;
      if (payment.memo !== order.memo) continue;
      if (order.instrumentId !== undefined && payment.instrument.id !== order.instrumentId) continue;
      // An instrument is (admin, id): the id is a reusable label, so without the
      // admin check a token minted under an attacker's admin but named `Amulet`
      // would settle a real order. Enforce the admin whenever the order pins one.
      if (order.instrumentAdmin !== undefined && payment.instrument.admin !== order.instrumentAdmin) continue;
      if (scaledAmount(payment.amount) < scaledAmount(order.amount)) continue;

      order.status = 'settled';
      order.settledAt = now.toISOString();
      order.settledBy = { updateId: payment.updateId, sender: payment.sender, amount: payment.amount };
      return order;
    }
    return null;
  }
}
