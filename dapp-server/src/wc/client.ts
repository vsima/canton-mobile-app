// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

// The WalletConnect Sign client factory. The dApp end of the round-trip is a
// Sign client talking to a wallet through the public relay. Each client in a
// process must have its own Core, or clients share one relay subscription and
// each other's storage; `customStoragePrefix` gives each its own. A projectId
// (free, from cloud.reown.com) authenticates to the public relay.

import { Core } from '@walletconnect/core';
import { SignClient } from '@walletconnect/sign-client';

/** A ready WalletConnect Sign client — whatever `SignClient.init` resolves to. */
export type WcSignClient = Awaited<ReturnType<typeof SignClient.init>>;

export interface WcConfig {
  /** WalletConnect Cloud project id — authenticates to the relay. */
  projectId: string;
  /** Relay WebSocket URL. Defaults to the public relay. */
  relayUrl?: string;
}

export const DEFAULT_RELAY_URL = 'wss://relay.walletconnect.org';

export interface WcMetadata {
  name: string;
  description: string;
  url: string;
  icons: string[];
}

/**
 * Creates an isolated Sign client. `storagePrefix` must be distinct per client
 * in the same process (e.g. `dapp` vs `wallet`) so their Cores don't collide.
 */
export async function makeSignClient(config: WcConfig, storagePrefix: string, metadata: WcMetadata): Promise<WcSignClient> {
  const relayUrl = config.relayUrl ?? DEFAULT_RELAY_URL;
  const core = new Core({ projectId: config.projectId, relayUrl, customStoragePrefix: storagePrefix });
  return SignClient.init({ core, metadata });
}
