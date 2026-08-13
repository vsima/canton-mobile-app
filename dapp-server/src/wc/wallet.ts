// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

// The wallet end of the WalletConnect round-trip — headless, so the whole loop
// runs with no phone, but doing exactly what a phone's WalletKit responder will:
// accept a pairing, approve a session that advertises its account, and answer
// each `session_request` by signing. What "signing" means is a CIP-0103 method
// dispatch (`dispatchWalletRequest`) delegated to a `WalletSigner`, kept free of
// both the WalletConnect and Canton SDKs so it can be unit-tested with a fake.
// The Canton-backed signer lives in ./signer.ts; the transport wiring is here.

import { formatJsonRpcResult, formatJsonRpcError } from '@walletconnect/jsonrpc-utils';
import type { SignClientTypes } from '@walletconnect/types';
import { makeSignClient } from './client.ts';
import type { WcConfig, WcMetadata, WcSignClient } from './client.ts';
import {
  ALL_METHODS,
  CANTON_METHODS,
  CANTON_NAMESPACE,
  WC_ERRORS,
  WcRequestError,
  chainId,
  toAccount,
} from './protocol.ts';
import type { DappAccount, RequestTransferParams, SignMessageParams } from './protocol.ts';

/**
 * What the wallet can do when a request arrives — the CIP-0103 provider surface
 * this reference implements. Implemented by the Canton-backed signer for real
 * runs, and by a fake in tests — neither the dispatch nor this interface knows
 * about WalletConnect.
 */
export interface WalletSigner {
  /** The account this wallet controls, as CIP-0103 `listAccounts` publishes it
   *  (its `publicKey` is what a Sign-In signature verifies against). */
  account(): DappAccount;
  /** Sign the CIP-0103 domain-separated bytes of `message`; returns the hex
   *  signature (the real `signMessage` result carries only the signature). */
  signMessage(message: string): Promise<string>;
  /** Approve and submit a transfer; returns the on-ledger update id. The
   *  non-standard convenience path — a native wallet uses `prepareExecute`. */
  submitTransfer(params: RequestTransferParams): Promise<{ updateId: string }>;
}

/**
 * Routes a decoded JSON-RPC request to the signer and shapes the result — the
 * real CIP-0103 methods (`connect`, `listAccounts`, `signMessage`, …) plus the
 * non-standard `canton_requestTransfer`. Pure: no relay, no SDK — the
 * unit-testable heart of the responder. Throws a {@link WcRequestError} (with a
 * CIP-0103 / EIP-1193 code) on an unknown method.
 */
export async function dispatchWalletRequest(
  request: { method: string; params: unknown },
  signer: WalletSigner,
): Promise<unknown> {
  switch (request.method) {
    case CANTON_METHODS.connect:
    case CANTON_METHODS.isConnected:
      return { isConnected: true, isNetworkConnected: true };
    case CANTON_METHODS.disconnect:
      return null;
    case CANTON_METHODS.listAccounts:
      return [signer.account()];
    case CANTON_METHODS.getPrimaryAccount:
      return signer.account();
    case CANTON_METHODS.signMessage: {
      const { message } = request.params as SignMessageParams;
      return { signature: await signer.signMessage(message) };
    }
    case CANTON_METHODS.requestTransfer: {
      const params = request.params as RequestTransferParams;
      const { updateId } = await signer.submitTransfer(params);
      return { updateId, sender: signer.account().partyId };
    }
    default:
      throw new WcRequestError(WC_ERRORS.unsupportedMethod, `unsupported method: ${request.method}`);
  }
}

export interface WalletEvents {
  /** Fired when a session is established, with its topic. */
  onSession?: (topic: string) => void;
  /** Fired for each request, before it is answered. */
  onRequest?: (method: string) => void;
}

/**
 * A WalletConnect Sign responder backed by a {@link WalletSigner}. It approves
 * any proposal (this is a demo wallet) with a single account — the signer's
 * party — and answers requests via {@link dispatchWalletRequest}.
 */
export class HeadlessWallet {
  private readonly client: WcSignClient;
  private readonly signer: WalletSigner;
  private readonly chain: string;
  private readonly events: WalletEvents;

  private constructor(client: WcSignClient, signer: WalletSigner, chain: string, events: WalletEvents) {
    this.client = client;
    this.signer = signer;
    this.chain = chain;
    this.events = events;
    this.listen();
  }

  static async create(
    config: WcConfig,
    signer: WalletSigner,
    networkId: string,
    metadata: WcMetadata,
    events: WalletEvents = {},
  ): Promise<HeadlessWallet> {
    const client = await makeSignClient(config, 'wallet', metadata);
    return new HeadlessWallet(client, signer, chainId(networkId), events);
  }

  /** Accept a pairing URI produced by the dApp. */
  async pair(uri: string): Promise<void> {
    await this.client.core.pairing.pair({ uri });
  }

  private listen(): void {
    this.client.on('session_proposal', (proposal: SignClientTypes.EventArguments['session_proposal']) => {
      void this.approve(proposal);
    });
    this.client.on('session_request', (event: SignClientTypes.EventArguments['session_request']) => {
      void this.answer(event);
    });
  }

  private async approve(proposal: SignClientTypes.EventArguments['session_proposal']): Promise<void> {
    const account = toAccount(this.chain, this.signer.account().partyId);
    const { acknowledged } = await this.client.approve({
      id: proposal.id,
      namespaces: {
        [CANTON_NAMESPACE]: { accounts: [account], methods: [...ALL_METHODS], events: [], chains: [this.chain] },
      },
    });
    const session = await acknowledged();
    this.events.onSession?.(session.topic);
  }

  private async answer(event: SignClientTypes.EventArguments['session_request']): Promise<void> {
    const { topic, id, params } = event;
    this.events.onRequest?.(params.request.method);
    try {
      const result = await dispatchWalletRequest(params.request, this.signer);
      await this.client.respond({ topic, response: formatJsonRpcResult(id, result) });
    } catch (e) {
      const code = e instanceof WcRequestError ? e.code : WC_ERRORS.internal;
      const message = e instanceof Error ? e.message : String(e);
      await this.client.respond({ topic, response: formatJsonRpcError(id, { code, message }) });
    }
  }
}
