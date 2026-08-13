// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

// A tiny storefront over the order book: a fixed catalog priced in Canton Coin,
// and a checkout that turns a product into a payable order. The reference is
// the flow — browse, check out, pay, settle — not the catalog, so the catalog
// is hardcoded.

import { scaledAmount, unscaledAmount, type OrderBook, type Order } from './orders.ts';

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

export interface CartItem {
  productId: string;
  quantity: number;
}

export interface LineItem {
  id: string;
  name: string;
  emoji: string;
  priceCc: string;
  quantity: number;
  subtotal: string;
}

export interface CartCheckout {
  order: Order;
  lineItems: LineItem[];
  total: string;
}

/**
 * Turns a cart into a single pending order the customer can pay: priced in
 * Canton Coin at the summed total, payable to the merchant party, referenced by
 * the order id in its memo. The order's description is the item summary the
 * wallet shows on review. Throws on an empty cart or an unknown product.
 */
export function checkoutCart(items: CartItem[], merchantParty: string, orders: OrderBook): CartCheckout {
  if (items.length === 0) throw new Error('cart is empty');
  const lineItems: LineItem[] = [];
  let totalScaled = 0n;
  for (const { productId, quantity } of items) {
    const product = findProduct(productId);
    if (product === undefined) throw new Error(`no such product: ${productId}`);
    const qty = Math.floor(quantity);
    if (!Number.isFinite(qty) || qty < 1) throw new Error(`invalid quantity for ${productId}: ${quantity}`);
    const subtotalScaled = scaledAmount(product.priceCc) * BigInt(qty);
    totalScaled += subtotalScaled;
    lineItems.push({
      id: product.id,
      name: product.name,
      emoji: product.emoji,
      priceCc: product.priceCc,
      quantity: qty,
      subtotal: unscaledAmount(subtotalScaled),
    });
  }
  const total = unscaledAmount(totalScaled);
  const description = lineItems.map((li) => `${li.quantity}× ${li.name}`).join(' · ');
  const order = orders.create({
    payTo: merchantParty,
    amount: total,
    instrumentId: INSTRUMENT_ID,
    description,
  });
  return { order, lineItems, total };
}
