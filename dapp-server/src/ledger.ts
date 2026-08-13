// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

// The ledger side of the dApp server: read the token-standard payments landing
// on a party, so the server can settle merchant-style orders. Built on the
// official @canton-network/wallet-sdk against a Splice LocalNet.
//
// This is read-only. The customer's wallet moves the funds; the server watches
// for the payment to land and marks the order paid — the realistic split, and
// one that needs no key on the server.

import { SDK, localNetStaticConfig, CustomLogAdapter } from '@canton-network/wallet-sdk';

/** Convention key for the human-readable transfer memo, shared with the wallet
 *  apps. The payer puts the order id here so the server can match the payment. */
export const MEMO_KEY = 'splice.lfdecentralizedtrust.org/reason';

export interface LedgerConfig {
  /** JSON Ledger API of the party's participant (LocalNet app-user: :2975). */
  ledgerClientUrl: string;
  /** Token registry (LocalNet: the validator scan-proxy). */
  registryUrl: string;
  /** Validator API, for registry choice context. */
  validatorUrl?: string;
  /** Ledger API user the self-signed dev token authenticates as. */
  authUserId: string;
  /** HS256 secret for the LocalNet dev token. */
  authSecret: string;
  /** Token audience. */
  audience: string;
}

/** A token-standard payment observed landing on a watched party. */
export interface IncomingPayment {
  updateId: string;
  offset: number;
  recordTime: string;
  sender: string;
  receiver: string;
  amount: string;
  instrument: { admin: string; id: string };
  /** The transfer memo, when the payment carried one — the order reference. */
  memo?: string;
}

/** LocalNet defaults, straight from the SDK's own static config. */
export function localNetLedgerConfig(authUserId?: string): LedgerConfig {
  const c = localNetStaticConfig;
  return {
    ledgerClientUrl: String(c.LOCALNET_APP_USER_LEDGER_URL),
    registryUrl: String(c.LOCALNET_REGISTRY_API_URL),
    validatorUrl: String(c.LOCALNET_APP_VALIDATOR_URL),
    authUserId: authUserId ?? String(c.LOCALNET_USER_ID),
    authSecret: 'unsafe',
    audience: 'https://canton.network.global',
  };
}

// warn/error to stderr; drop info/debug/trace — the SDK logs the minted JWT at
// info, which a reference server should not spew.
const quietLog = new CustomLogAdapter((level: string, _ctx: unknown, message?: string) => {
  if (level === 'error' || level === 'warn') console.error(`[wallet-sdk:${level}] ${message ?? ''}`);
});

export class Ledger {
  // The extended SDK is deeply generic; the reads below navigate plain JSON, so
  // an opaque handle keeps the surface honest without fighting the type system.
  private readonly sdk: any;

  private constructor(sdk: unknown) {
    this.sdk = sdk;
  }

  static async connect(config: LedgerConfig): Promise<Ledger> {
    const auth = {
      method: 'self_signed' as const,
      issuer: 'unsafe-auth',
      credentials: {
        clientId: config.authUserId,
        clientSecret: config.authSecret,
        audience: config.audience,
        scope: '',
      },
    };
    const base = await SDK.create({ auth, ledgerClientUrl: config.ledgerClientUrl, logAdapter: quietLog });
    const sdk = await base.extend({
      token: { auth, registries: [config.registryUrl], validatorUrl: config.validatorUrl },
    });
    return new Ledger(sdk);
  }

  /** The current ledger end offset — a cursor to start watching from "now". */
  async ledgerEnd(): Promise<number> {
    return this.sdk.ledger.ledgerEnd();
  }

  /**
   * Token-standard payments that landed on `partyId` after `afterOffset`,
   * newest offset last, plus the offset to resume the next poll from. Only
   * incoming transfers (`TransferIn`) are returned — the party's own sends and
   * merge/splits are ignored.
   */
  async incomingPayments(
    partyId: string,
    afterOffset?: number,
  ): Promise<{ payments: IncomingPayment[]; nextOffset: number }> {
    const params = afterOffset !== undefined ? { partyId, afterOffset } : { partyId };
    const res = await this.sdk.token.holdings(params);
    const payments: IncomingPayment[] = [];
    for (const tx of res.transactions ?? []) {
      for (const event of tx.events ?? []) {
        if (event?.label?.type !== 'TransferIn') continue;
        const payment = extractIncoming(tx, event, partyId);
        if (payment !== null) payments.push(payment);
      }
    }
    return { payments, nextOffset: res.nextOffset };
  }
}

/**
 * Pulls an `IncomingPayment` out of a parsed `TransferIn` event. The transfer
 * detail (amount, receiver, memo) lives under `transferInstruction.transfer`;
 * the per-instrument `unlockedHoldingsChangeSummaries` is the fallback for the
 * amount/instrument when a direct (preapproved) receive carries no instruction.
 */
function extractIncoming(tx: any, event: any, party: string): IncomingPayment | null {
  const label = event?.label;
  const transfer = event?.transferInstruction?.transfer;
  const summary = event?.unlockedHoldingsChangeSummaries?.[0];
  const instrument = transfer?.instrumentId ?? summary?.instrumentId;
  const amount = transfer?.amount ?? summary?.amountChange ?? summary?.outputAmount;
  if (instrument == null || amount == null) return null;
  // The transfer memo is the parsed `reason` on the label — present whether the
  // transfer settled directly (preapproved, no instruction) or via an
  // offer→accept instruction. The instruction's transfer meta is a fallback.
  const reason = label?.reason;
  const metaMemo = transfer?.meta?.values?.[MEMO_KEY];
  const memo =
    typeof reason === 'string' && reason !== ''
      ? reason
      : typeof metaMemo === 'string' && metaMemo !== ''
        ? metaMemo
        : undefined;
  return {
    updateId: String(tx.updateId ?? ''),
    offset: Number(tx.offset ?? 0),
    recordTime: String(tx.recordTime ?? ''),
    sender: String(label?.sender ?? transfer?.sender ?? ''),
    receiver: String(transfer?.receiver ?? party),
    amount: String(amount),
    instrument: { admin: String(instrument.admin ?? ''), id: String(instrument.id ?? '') },
    memo,
  };
}
