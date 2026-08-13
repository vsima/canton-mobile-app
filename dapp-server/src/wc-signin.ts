// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

// Sign-In with Canton over WalletConnect, driven for a REAL phone.
//
// Unlike wc-demo (headless — both ends in one process), this is the dApp end
// only: it opens a WalletConnect session, prints the pairing URI as a scannable
// terminal QR, waits for a wallet to pair and approve, then runs the real
// CIP-0103 sign-in — connect → listAccounts → signMessage — and verifies the
// signature against the account's published key. Point a Canton wallet's
// Connect tab at the QR.
//
// Needs a WalletConnect project id (free, cloud.reown.com) in WC_PROJECT_ID.
// No LocalNet needed: signing is pure crypto, and this side only verifies.

import { randomUUID } from 'node:crypto';
import QRCode from 'qrcode';
import { DappConnector } from './wc/dapp.ts';
import { buildSignInMessage, verifySignature } from './siwc.ts';

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
  const { uri, approved } = await dapp.createSession();
  console.log('\n' + (await QRCode.toString(uri, { type: 'terminal', small: true })));
  note('scan this with your Canton wallet (Connect tab), or paste the URI:');
  console.log(dim(uri) + '\n');

  step('②', 'waiting for the wallet to pair and approve…');
  const session = await approved;
  note(`session ${session.topic.slice(0, 12)}…`);

  step('③', 'connecting (approve on your phone)…');
  await dapp.connect(session.topic);
  const accounts = await dapp.listAccounts(session.topic);
  const acct = accounts[0];
  if (acct === undefined) throw new Error('the wallet shared no accounts');
  note(`account ${acct.partyId.slice(0, 44)}…`);

  step('④', 'requesting a Sign-In signature (approve on your phone)…');
  const message = buildSignInMessage({
    domain: DOMAIN,
    party: acct.partyId,
    statement: `Sign in to ${SHOP_NAME}.`,
    uri: LOGIN_URI,
    networkId: NETWORK_ID,
    nonce: randomUUID().replace(/-/g, ''),
    issuedAt: new Date().toISOString(),
  });
  const { signature } = await dapp.requestSignMessage(session.topic, message);

  step('⑤', 'verifying the signature…');
  const ok = verifySignature(message, acct.publicKey, Buffer.from(signature, 'hex'));
  if (!ok) throw new Error('the signature did not verify against the account key');
  console.log(green(`\n✓ SIGNED IN — ${acct.partyId} authenticated over WalletConnect, signed on the phone.`));

  await dapp.disconnect(session.topic).catch(() => {});
}

main().then(() => process.exit(0)).catch((e) => {
  console.error(`\nwc-signin failed: ${(e as Error).message}`);
  process.exit(1);
});
