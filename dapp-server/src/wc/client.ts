// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

// The shared WalletConnect Sign client factory. Both ends of the round-trip —
// the dApp and the headless wallet — are WalletConnect Sign clients talking
// through the same relay; only their roles differ. Two clients in one process
// must NOT share a Core, or they share one relay subscription and each other's
// storage; `customStoragePrefix` gives each its own. A projectId (free, from
// cloud.reown.com) authenticates to the public relay.

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
