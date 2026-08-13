// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

// Offline coverage for the WalletConnect transport's transport-agnostic parts:
// the CAIP identifier encoding (where Canton party ids collide with WalletConnect
// rules), the Ed25519 signing helpers (against siwc's own verifier), and the
// request dispatch (against a fake signer). The live relay round-trip is
// `npm run wc-demo`, which needs a project id and a running shop + LocalNet — the
// same way `npm run demo` needs infrastructure the unit tests don't.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { generateKeyPairSync } from 'node:crypto';

import {
  CANTON_METHODS,
  WcRequestError,
  WC_ERRORS,
  chainId,
  encodePartyAddress,
  decodePartyAddress,
  toAccount,
  partyFromAccount,
} from '../src/wc/protocol.ts';
import { publicKeyToSpkiHex, signDomainMessageHex } from '../src/wc/crypto.ts';
import { dispatchWalletRequest } from '../src/wc/wallet.ts';
import type { WalletSigner } from '../src/wc/wallet.ts';
import { verifySignature } from '../src/siwc.ts';

// --- CAIP identifiers -------------------------------------------------------

test('party ids round-trip through a CAIP-10 address (:: and _ are encoded)', () => {
  const parties = [
    'preapproved::1220b3d98dd0362a19385d6878be4bafb2f12f13531ee7abcb8f32bdb2d764bac9be',
    'my_shop::1220abcDEF',
    'shopper::1220deadbeef',
  ];
  for (const party of parties) {
    const addr = encodePartyAddress(party);
    assert.doesNotMatch(addr, /:/, 'the CAIP-10 address segment must not contain a colon');
    assert.match(addr, /^[-.%a-zA-Z0-9]+$/, 'must be within the CAIP-10 address charset');
    assert.equal(decodePartyAddress(addr), party);
  }
});

test('toAccount / partyFromAccount round-trip against the CAIP-2 chain', () => {
  const party = 'preapproved::1220b3d98dd0362a19385d6878be4bafb2f12f13531ee7abcb8f32bdb2d764bac9be';
  const account = toAccount('canton:localnet', party);
  assert.ok(account.startsWith('canton:localnet:'));
  assert.equal(partyFromAccount(account), party);
});

test('chainId validates a CAIP-2 network id', () => {
  assert.equal(chainId('canton:localnet'), 'canton:localnet');
  assert.throws(() => chainId('not-a-chain'), /CAIP-2/);
  assert.throws(() => chainId('canton:localnet:extra'), /CAIP-2/);
});

// --- signing ----------------------------------------------------------------

// Build a base64 raw Ed25519 keypair the way the SDK's keys.generate() would,
// but from node's own generator: the last 32 bytes of the SPKI/PKCS8 DER are the
// raw public key / seed.
function rawEd25519(): { publicKey: string; privateKey: string; spkiHex: string } {
  const { publicKey, privateKey } = generateKeyPairSync('ed25519');
  const spkiDer = publicKey.export({ type: 'spki', format: 'der' });
  const pkcs8Der = privateKey.export({ type: 'pkcs8', format: 'der' });
  return {
    publicKey: spkiDer.subarray(-32).toString('base64'),
    privateKey: pkcs8Der.subarray(-32).toString('base64'),
    spkiHex: spkiDer.toString('hex'),
  };
}

test('publicKeyToSpkiHex matches node’s own SPKI-DER encoding', () => {
  const { publicKey, spkiHex } = rawEd25519();
  assert.equal(publicKeyToSpkiHex(publicKey), spkiHex);
});

test('signDomainMessageHex produces a signature siwc verifies (and rejects tampering)', () => {
  const { publicKey, privateKey } = rawEd25519();
  const message = 'localhost:8088 wants you to sign in with your Canton account:\n…';
  const spkiHex = publicKeyToSpkiHex(publicKey);
  const sig = Buffer.from(signDomainMessageHex(message, privateKey), 'hex');
  assert.equal(verifySignature(message, spkiHex, sig), true);
  assert.equal(verifySignature(message + 'x', spkiHex, sig), false);
});

// --- request dispatch -------------------------------------------------------

function fakeSigner(): { signer: WalletSigner; transfers: unknown[] } {
  const transfers: unknown[] = [];
  const signer: WalletSigner = {
    party: 'shopper::1220abc',
    async signMessage() {
      return { signature: 'aa11', publicKey: 'bb22' };
    },
    async submitTransfer(params) {
      transfers.push(params);
      return { updateId: 'update-1' };
    },
  };
  return { signer, transfers };
}

test('dispatch: canton_signMessage returns signature, party and publicKey', async () => {
  const { signer } = fakeSigner();
  const result = await dispatchWalletRequest(
    { method: CANTON_METHODS.signMessage, params: { message: 'hi' } },
    signer,
  );
  assert.deepEqual(result, { signature: 'aa11', party: 'shopper::1220abc', publicKey: 'bb22' });
});

test('dispatch: canton_requestTransfer submits and returns updateId + sender', async () => {
  const { signer, transfers } = fakeSigner();
  const params = { to: 'merchant::1220aa', amount: '12', instrument: 'Amulet', memo: 'order-1' };
  const result = await dispatchWalletRequest(
    { method: CANTON_METHODS.requestTransfer, params },
    signer,
  );
  assert.deepEqual(result, { updateId: 'update-1', sender: 'shopper::1220abc' });
  assert.deepEqual(transfers, [params]);
});

test('dispatch: an unknown method rejects with the unsupported-method code', async () => {
  const { signer } = fakeSigner();
  await assert.rejects(
    () => dispatchWalletRequest({ method: 'canton_bogus', params: {} }, signer),
    (e: unknown) => e instanceof WcRequestError && e.code === WC_ERRORS.unsupportedMethod,
  );
});
