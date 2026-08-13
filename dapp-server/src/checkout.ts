// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

// The scan-to-pay payload. The checkout screen shows a QR a wallet scans (in
// app, or via the phone camera as a deep link) to review and pay.
//
// It's a HYBRID, self-describing payload: the display + send fields are inline,
// so a wallet prefills instantly and offline — no fetch, no dependency on the
// server being reachable, no scan-time ping to the merchant. An optional `url`
// points at the order for a wallet that wants authoritative or live state.
//
// Canton has no payment-URI standard (the CIPs define none), so this is a small
// convention of this reference, modelled on EIP-681.

import type { Order } from './orders.ts';

export const CHECKOUT_SCHEME = 'canton-checkout';

export interface CheckoutUriFields {
  publicUrl: string;
  orderId: string;
  payTo: string;
  amount: string;
  instrument: string;
  memo: string;
  shop: string;
  item: string;
}

/**
 * The hybrid `canton-checkout://pay?…` payload. Values are percent-encoded with
 * `encodeURIComponent` (spaces as %20, not +), which `Uri.getQueryParameter`
 * (Android) and `URLComponents.queryItems` (iOS) both decode cleanly.
 */
export function checkoutUri(f: CheckoutUriFields): string {
  const base = f.publicUrl.replace(/\/+$/, '');
  const params: Array<[string, string]> = [
    ['to', f.payTo],
    ['amount', f.amount],
    ['instrument', f.instrument],
    ['memo', f.memo],
    ['shop', f.shop],
    ['item', f.item],
    ['url', `${base}/checkout/${f.orderId}`],
  ];
  const query = params.map(([k, v]) => `${k}=${encodeURIComponent(v)}`).join('&');
  return `${CHECKOUT_SCHEME}://pay?${query}`;
}

/** What a wallet fetches via the payload's `url` — the authoritative order, for
 *  a wallet that wants live state. The inline fields cover the common path. */
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
