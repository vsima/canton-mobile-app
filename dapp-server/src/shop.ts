// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

// A tiny storefront over the order book: a fixed catalog priced in Canton Coin,
// and a checkout that turns a product into a payable order. The reference is
// the flow — browse, check out, pay, settle — not the catalog, so the catalog
// is hardcoded.

import type { OrderBook, Order } from './orders.ts';

export interface Product {
  id: string;
  name: string;
  /** Price in Canton Coin (the `Amulet` instrument), as a decimal string. */
  priceCc: string;
  emoji: string;
}

/** Everything is priced in `Amulet` — the instrument an order matches against. */
export const INSTRUMENT_ID = 'Amulet';

export const CATALOG: Product[] = [
  { id: 'coffee', name: 'Coffee', priceCc: '5', emoji: '☕' },
  { id: 'stickers', name: 'Sticker pack', priceCc: '2', emoji: '✨' },
  { id: 'mug', name: 'Enamel mug', priceCc: '8', emoji: '🍵' },
  { id: 'tshirt', name: 'T-shirt', priceCc: '20', emoji: '👕' },
];

export function findProduct(id: string): Product | undefined {
  return CATALOG.find((p) => p.id === id);
}

export interface CheckoutResult {
  product: Product;
  order: Order;
}

/**
 * Turns a product into a pending order the customer can pay: priced in Canton
 * Coin, payable to the merchant party, referenced by the order id in its memo.
 * Throws on an unknown product.
 */
export function checkout(productId: string, merchantParty: string, orders: OrderBook): CheckoutResult {
  const product = findProduct(productId);
  if (product === undefined) throw new Error(`no such product: ${productId}`);
  const order = orders.create({ payTo: merchantParty, amount: product.priceCc, instrumentId: INSTRUMENT_ID });
  return { product, order };
}
