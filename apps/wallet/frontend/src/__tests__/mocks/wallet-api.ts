// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { RestHandler, rest } from 'msw';
import { LookupTransferPreapprovalByPartyResponse } from 'scan-openapi';
import {
  GetAmuletRulesProxyResponse,
  GetOpenAndIssuingMiningRoundsProxyResponse,
  LookupEntryByPartyResponse,
} from 'scan-proxy-openapi';
import { Mock, vi } from 'vitest';
import { ListTransferOffersResponse } from 'wallet-external-openapi';
import { GetBalanceResponse, ListTransactionsResponse, UserStatusResponse } from 'wallet-openapi';

import {
  aliceEntry,
  alicePartyId,
  amuletRules,
  bobTransferPreapproval,
  miningRounds,
  nameServiceEntries,
} from './constants';

export const requestMocks: {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  createTransferOffer: Mock<(request: any) => Promise<any>>;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  createTransferPreapproval: Mock<() => Promise<any>>;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  transferPreapprovalSend: Mock<(request: any) => Promise<any>>;
} = {
  createTransferOffer: vi.fn(),
  createTransferPreapproval: vi.fn(),
  transferPreapprovalSend: vi.fn(),
};

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
    `${walletUrl}/v0/scan-proxy/featured-apps/alice__wallet__user%3A%3A12201d5aa725ec9491490fd860e86f849358604f6fd387053771cafb90384a94c3e2`,
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

  rest.get(`${walletUrl}/v0/scan-proxy/ans-entries/by-party/:party`, (req, res, ctx) => {
    const { party } = req.params;
    if (
      party ===
      'alice__wallet__user::12201d5aa725ec9491490fd860e86f849358604f6fd387053771cafb90384a94c3e2'
    ) {
      return res(
        ctx.json<LookupEntryByPartyResponse>({
          entry: aliceEntry,
        })
      );
    } else {
      return res(ctx.status(404), ctx.json({}));
    }
  }),

  rest.get(`${walletUrl}/v0/scan-proxy/ans-entries/by-name/:name`, (_, res, ctx) => {
    return res(ctx.status(404), ctx.json({}));
  }),

  rest.get(`${walletUrl}/v0/sample`, (_, res, ctx) => {
    return res(ctx.json({}));
  }),

  rest.get(`${walletUrl}/v0/scan-proxy/transfer-preapprovals/by-party/:party`, (req, res, ctx) => {
    const { party } = req.params;
    if (party === 'bob::preapproval') {
      return res(
        ctx.json<LookupTransferPreapprovalByPartyResponse>({
          transfer_preapproval: bobTransferPreapproval,
        })
      );
    }
    return res(ctx.status(404), ctx.json({}));
  }),

  rest.get(`${walletUrl}/v0/scan-proxy/transfer-preapprovals/by-party`, (_req, res, ctx) => {
    // The by-party request above seems to not match for an empty party string
    return res(ctx.status(404), ctx.json({}));
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

  rest.post(`${walletUrl}/v0/wallet/transfer-offers`, async (req, res, ctx) => {
    requestMocks.createTransferOffer(await req.json());
    return res(ctx.json({}));
  }),

  rest.post(`${walletUrl}/v0/wallet/transfer-preapproval`, async (_, res, ctx) => {
    requestMocks.createTransferPreapproval();
    return res(ctx.json({}));
  }),

  rest.post(`${walletUrl}/v0/wallet/transfer-preapproval/send`, async (req, res, ctx) => {
    requestMocks.transferPreapprovalSend(await req.json());
    return res(ctx.json({}));
  }),

  rest.post(`${walletUrl}/v0/wallet/transactions`, async (_, res, ctx) => {
    return res(ctx.json<ListTransactionsResponse>({ items: [] }));
  }),

  rest.get(`${walletUrl}/v0/scan-proxy/amulet-rules`, (_, res, ctx) => {
    return res(ctx.json<GetAmuletRulesProxyResponse>(amuletRules));
  }),

  rest.get(`${walletUrl}/v0/scan-proxy/featured-apps/:party`, (_, res, ctx) => {
    return res(ctx.status(404), ctx.json({}));
  }),
];
