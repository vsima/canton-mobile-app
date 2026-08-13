// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

// The dApp end of the WalletConnect round-trip — the side a shop's front-end
// runs. It opens a session (the QR/URI a wallet scans), then pushes CIP-0103
// requests over it: `requestSignMessage` to authenticate a party (Sign-In with
// Canton, over a live session instead of a scanned challenge) and
// `requestTransfer` to ask the wallet to pay. The wallet holds the keys; this
// side only asks and reads the reply.

import type { SessionTypes } from '@walletconnect/types';
import { makeSignClient } from './client.ts';
import type { WcConfig, WcMetadata, WcSignClient } from './client.ts';
import { ALL_METHODS, CANTON_METHODS, CANTON_NAMESPACE, chainId } from './protocol.ts';
import type { RequestTransferParams, SignMessageResult, TransferResult } from './protocol.ts';

export class DappConnector {
  private readonly client: WcSignClient;
  private readonly chain: string;

  private constructor(client: WcSignClient, chain: string) {
    this.client = client;
    this.chain = chain;
  }

  static async create(config: WcConfig, networkId: string, metadata: WcMetadata): Promise<DappConnector> {
    const client = await makeSignClient(config, 'dapp', metadata);
    return new DappConnector(client, chainId(networkId));
  }

  /**
   * Opens a session. Returns the pairing `uri` (render it as the QR a wallet
   * scans) and `approved`, which resolves once a wallet has paired and
   * approved. Advertised as optional namespaces — the modern WalletConnect
   * shape; a wallet approves the subset it supports.
   */
  async createSession(): Promise<{ uri: string; approved: Promise<SessionTypes.Struct> }> {
    const { uri, approval } = await this.client.connect({
      optionalNamespaces: {
        [CANTON_NAMESPACE]: { chains: [this.chain], methods: [...ALL_METHODS], events: [] },
      },
    });
    if (uri === undefined) throw new Error('WalletConnect returned no pairing URI');
    return { uri, approved: approval() };
  }

  /** Ask the wallet to sign a message (Sign-In with Canton over the session). */
  async requestSignMessage(topic: string, message: string): Promise<SignMessageResult> {
    return this.client.request<SignMessageResult>({
      topic,
      chainId: this.chain,
      request: { method: CANTON_METHODS.signMessage, params: { message } },
    });
  }

  /** Ask the wallet to approve and submit a transfer. */
  async requestTransfer(topic: string, params: RequestTransferParams): Promise<TransferResult> {
    return this.client.request<TransferResult>({
      topic,
      chainId: this.chain,
      request: { method: CANTON_METHODS.requestTransfer, params },
    });
  }

  /** Close a session and release the relay connection. */
  async disconnect(topic: string): Promise<void> {
    await this.client.disconnect({ topic, reason: { code: 6000, message: 'done' } });
  }
}
