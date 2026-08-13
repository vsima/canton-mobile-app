// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

// The Canton-backed WalletSigner: the one place the headless wallet touches the
// Canton SDK. `signMessage` produces a CIP-0103 signature the dApp verifies
// against the party's key; `submitTransfer` runs the external-party pipeline —
// token.transfer.create → ledger.prepare → sign → execute — the same steps the
// direct `demo.ts` uses, now driven by a request that arrived over the session.

import { publicKeyToSpkiHex, signDomainMessageHex } from './crypto.ts';
import type { WalletSigner } from './wallet.ts';
import type { DappAccount, RequestTransferParams } from './protocol.ts';
import type { LocalNetSdk } from '../localnet.ts';

export interface CantonSignerOptions {
  sdk: LocalNetSdk;
  /** The party's Ed25519 keypair, base64 (as `sdk.keys.generate()` returns). */
  keys: { publicKey: string; privateKey: string };
  /** The party this wallet controls and pays from. */
  party: string;
  /** Registry URL used when building the transfer. */
  registryUrl: URL;
  /** CAIP-2 network id published in the account. */
  networkId: string;
}

export function cantonWalletSigner(opts: CantonSignerOptions): WalletSigner {
  const account: DappAccount = {
    primary: true,
    partyId: opts.party,
    status: 'allocated',
    hint: opts.party.split('::')[0] ?? '',
    publicKey: publicKeyToSpkiHex(opts.keys.publicKey),
    namespace: opts.party.split('::')[1] ?? '',
    networkId: opts.networkId,
    signingProviderId: 'headless',
  };
  return {
    account: () => account,

    async signMessage(message: string): Promise<string> {
      return signDomainMessageHex(message, opts.keys.privateKey);
    },

    async submitTransfer(params: RequestTransferParams): Promise<{ updateId: string }> {
      const [command, disclosedContracts] = (await opts.sdk.token.transfer.create({
        sender: opts.party,
        recipient: params.to,
        amount: params.amount,
        instrumentId: params.instrument,
        registryUrl: opts.registryUrl,
        memo: params.memo,
      })) as readonly [unknown, unknown[]];

      const prepared = opts.sdk.ledger.prepare({
        partyId: opts.party,
        commands: command,
        disclosedContracts: disclosedContracts as never,
      });
      const res = await prepared.sign(opts.keys.privateKey).execute({ partyId: opts.party });
      return { updateId: String(res.updateId) };
    },
  };
}
