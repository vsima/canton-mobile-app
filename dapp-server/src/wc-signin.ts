// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

// Sign-In with Canton over WalletConnect, driven for a REAL phone (the CLI form).
//
// This is the dApp end only: it opens a WalletConnect session, prints the
// pairing URI as a scannable terminal QR, and waits for a wallet to pair,
// approve, and sign. The storefront server drives the same `beginWalletConnectSignIn`
// step from a browser button; this is the terminal twin of it.
//
// Needs a WalletConnect project id (free, cloud.reown.com) in WC_PROJECT_ID.
// No LocalNet needed: signing is pure crypto, and this side only verifies.

import QRCode from 'qrcode';
import { DappConnector } from './wc/dapp.ts';
import { beginWalletConnectSignIn } from './wc/signin.ts';

const NETWORK_ID = process.env['DAPP_NETWORK_ID'] ?? 'canton:localnet';
const SHOP_NAME = process.env['SHOP_NAME'] ?? 'Canton Corner';
const DOMAIN = process.env['DAPP_DOMAIN'] ?? 'localhost:8088';
const LOGIN_URI = process.env['DAPP_URI'] ?? `http://${DOMAIN}/login`;
const RELAY_URL = process.env['WC_RELAY_URL'];
const PROJECT_ID = process.env['WC_PROJECT_ID'];

const cyan = (s: string) => `\x1b[36m${s}\x1b[0m`;
const green = (s: string) => `\x1b[32m${s}\x1b[0m`;
const dim = (s: string) => `\x1b[2m${s}\x1b[0m`;
function step(n: string, msg: string): void { console.log(`${cyan(n)} ${msg}`); }
function note(msg: string): void { console.log(`   ${dim(msg)}`); }

async function main(): Promise<void> {
  if (PROJECT_ID === undefined || PROJECT_ID === '') {
    throw new Error('set WC_PROJECT_ID (a free WalletConnect project id from cloud.reown.com)');
  }
  const wc = { projectId: PROJECT_ID, ...(RELAY_URL !== undefined ? { relayUrl: RELAY_URL } : {}) };

  const dapp = await DappConnector.create(wc, NETWORK_ID, {
    name: SHOP_NAME,
    description: 'Sign in with your Canton wallet',
    url: `http://${DOMAIN}`,
    icons: [],
  });

  step('①', 'opening a WalletConnect session…');
  const { uri, done } = await beginWalletConnectSignIn(dapp, {
    domain: DOMAIN,
    loginUri: LOGIN_URI,
    networkId: NETWORK_ID,
    shopName: SHOP_NAME,
  });
  console.log('\n' + (await QRCode.toString(uri, { type: 'terminal', small: true })));
  note('scan this with your Canton wallet (Connect tab), or paste the URI:');
  console.log(dim(uri) + '\n');

  step('②', 'waiting for the wallet to pair, approve, and sign…');
  const { party } = await done;
  console.log(green(`\n✓ SIGNED IN — ${party} authenticated over WalletConnect, signed on the phone.`));
}

main().then(() => process.exit(0)).catch((e) => {
  console.error(`\nwc-signin failed: ${(e as Error).message}`);
  process.exit(1);
});
