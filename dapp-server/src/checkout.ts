// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

// The order-fetch "session" flow. The checkout screen shows a QR that encodes
// where to fetch the order; a wallet scans it, GETs the checkout, shows it for
// review, and the customer pays. The web confirms by watching the ledger.
//
// Canton has no payment-URI standard (checked the CIPs), so this is a small
// convention: `canton-checkout:<url>`. The scheme only marks the QR as a
// Canton checkout to review-and-pay; the URL is where the wallet reads it.

import type { Order } from './orders.ts';

export const CHECKOUT_SCHEME = 'canton-checkout:';

/** The QR payload: the scheme marker followed by the URL the wallet fetches. */
export function checkoutUri(publicUrl: string, orderId: string): string {
  const base = publicUrl.replace(/\/+$/, '');
  return `${CHECKOUT_SCHEME}${base}/checkout/${orderId}`;
}

/** What the wallet fetches and renders for review — the reproduced checkout. */
export interface CheckoutView {
  orderId: string;
  shop: string;
  item: string | null;
  amount: string;
  instrumentId: string | null;
  payTo: string;
  memo: string;
  status: 'pending' | 'settled';
}

export function checkoutView(order: Order, shop: string): CheckoutView {
  return {
    orderId: order.id,
    shop,
    item: order.description ?? null,
    amount: order.amount,
    instrumentId: order.instrumentId ?? null,
    payTo: order.payTo,
    memo: order.memo,
    status: order.status,
  };
}
