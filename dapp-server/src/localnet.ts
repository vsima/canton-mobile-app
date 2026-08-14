// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

// Shared LocalNet wiring. The `demo.ts` customer and the server's one-tap
// payment push both allocate a party, tap funds, and transfer on the same
// LocalNet with the same dev auth, so the SDK setup lives here once rather than
// being repeated at each call site.

import { SDK, localNetStaticConfig, CustomLogAdapter } from '@canton-network/wallet-sdk';

/** The SDK's built-in LocalNet endpoint/config bundle. */
export const LOCALNET = localNetStaticConfig;

/** LocalNet's unsafe self-signed dev auth — never anything but LocalNet. */
export const localnetAuth = {
  method: 'self_signed' as const,
  issuer: 'unsafe-auth',
  credentials: {
    clientId: String(LOCALNET.LOCALNET_USER_ID),
    clientSecret: 'unsafe',
    audience: 'https://canton.network.global',
    scope: '',
  },
};

/** Registry URL used when building a transfer. */
export const localnetRegistryUrl = new URL(String(LOCALNET.LOCALNET_REGISTRY_API_URL));

/** A log adapter that stays quiet except for SDK errors, so a demo's own step
 *  trace is readable. */
export function quietLogAdapter(): CustomLogAdapter {
  return new CustomLogAdapter((level: string, _ctx: unknown, message?: string) => {
    if (level === 'error') console.error(`[sdk] ${message ?? ''}`);
  });
}

/** Creates an SDK handle extended with the token, amulet and (inherited) ledger
 *  namespaces the demos need, pointed at LocalNet. */
export async function createLocalNetSdk() {
  const base = await SDK.create({
    auth: localnetAuth,
    ledgerClientUrl: LOCALNET.LOCALNET_APP_USER_LEDGER_URL,
    logAdapter: quietLogAdapter(),
  });
  return base.extend({
    token: { auth: localnetAuth, registries: [LOCALNET.LOCALNET_REGISTRY_API_URL], validatorUrl: LOCALNET.LOCALNET_APP_VALIDATOR_URL },
    amulet: { auth: localnetAuth, scanApiUrl: LOCALNET.LOCALNET_SCAN_API_URL, registryUrl: LOCALNET.LOCALNET_REGISTRY_API_URL, validatorUrl: LOCALNET.LOCALNET_APP_VALIDATOR_URL },
  });
}

/** The fully-extended SDK handle the demos share. */
export type LocalNetSdk = Awaited<ReturnType<typeof createLocalNetSdk>>;
