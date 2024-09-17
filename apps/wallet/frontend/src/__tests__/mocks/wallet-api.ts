// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { RestHandler, rest } from 'msw';
import {
  GetAmuletRulesProxyResponse,
  GetOpenAndIssuingMiningRoundsProxyResponse,
  LookupEntryByPartyResponse,
} from 'scan-proxy-openapi';
import { ListTransferOffersResponse } from 'wallet-external-openapi';
import { GetBalanceResponse, ListTransactionsResponse, UserStatusResponse } from 'wallet-openapi';

import {
  aliceEntry,
  alicePartyId,
  amuletRules,
  miningRounds,
  nameServiceEntries,
} from './constants';

export const buildWalletMock = (walletUrl: string): RestHandler[] => [
  rest.get(`${walletUrl}/v0/wallet/user-status`, (_, res, ctx) => {
    return res(
      ctx.json<UserStatusResponse>({
        party_id: alicePartyId,
        user_onboarded: true,
        user_wallet_installed: true,
        has_featured_app_right: false,
      })
    );
  }),
  rest.get(
    `${walletUrl}v0/scan-proxy/featured-apps/alice__wallet__user%3A%3A12201d5aa725ec9491490fd860e86f849358604f6fd387053771cafb90384a94c3e2`,
    (_, res, ctx) => {
      return res(ctx.json({ featured_app_right: null }));
    }
  ),

  rest.get(`${walletUrl}/v0/scan-proxy/open-and-issuing-mining-rounds`, (_, res, ctx) => {
    return res(ctx.json<GetOpenAndIssuingMiningRoundsProxyResponse>(miningRounds));
  }),

  rest.get(`${walletUrl}/v0/scan-proxy/ans-entries?name_prefix=&page_size=20`, (_, res, ctx) => {
    return res(
      ctx.json({
        entries: nameServiceEntries,
      })
    );
  }),

  rest.get(
    `${walletUrl}/v0/scan-proxy/ans-entries/by-party/alice__wallet__user%3A%3A12201d5aa725ec9491490fd860e86f849358604f6fd387053771cafb90384a94c3e2`,
    (_, res, ctx) => {
      return res(
        ctx.json<LookupEntryByPartyResponse>({
          entry: aliceEntry,
        })
      );
    }
  ),

  rest.get(`${walletUrl}/v0/sample`, (_, res, ctx) => {
    return res(ctx.json({}));
  }),

  rest.get(`${walletUrl}/v0/wallet/balance`, (_, res, ctx) => {
    return res(
      ctx.json<GetBalanceResponse>({
        round: 18,
        effective_unlocked_qty: '778.9353119400',
        effective_locked_qty: '0.0000000000',
        total_holding_fees: '0.0646880600',
      })
    );
  }),

  rest.get(`${walletUrl}/v0/wallet/transactions`, (_, res, ctx) => {
    return res(ctx.json<ListTransactionsResponse>({ items: [] }));
  }),

  rest.get(`${walletUrl}/v0/wallet/transfer-offers`, (_, res, ctx) => {
    return res(ctx.json<ListTransferOffersResponse>({ offers: [] }));
  }),

  rest.get(`${walletUrl}/v0/scan-proxy/amulet-rules`, (_, res, ctx) => {
    return res(ctx.json<GetAmuletRulesProxyResponse>(amuletRules));
  }),
];
