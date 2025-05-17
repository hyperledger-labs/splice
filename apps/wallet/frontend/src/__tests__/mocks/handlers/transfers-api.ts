// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { rest, RestHandler } from 'msw';
import { Mock, vi } from 'vitest';

export const requestMocks: {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  createTransferOffer: Mock<(request: any) => Promise<any>>;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  createTransferViaTokenStandard: Mock<(request: any) => Promise<any>>;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  createTransferPreapproval: Mock<() => Promise<any>>;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  transferPreapprovalSend: Mock<(request: any) => Promise<any>>;
} = {
  createTransferOffer: vi.fn(),
  createTransferViaTokenStandard: vi.fn(),
  createTransferPreapproval: vi.fn(),
  transferPreapprovalSend: vi.fn(),
};

// We need to separate the api endpoints that use vitest to avoid compatibility errors with msw.
export const buildTransferOfferMock = (walletUrl: string): RestHandler[] => [
  rest.post(`${walletUrl}/v0/wallet/transfer-offers`, async (req, res, ctx) => {
    await requestMocks.createTransferOffer(await req.json());
    return res(ctx.json({}));
  }),

  rest.post(`${walletUrl}/v0/wallet/token-standard/transfers`, async (req, res, ctx) => {
    await requestMocks.createTransferViaTokenStandard(await req.json());
    return res(ctx.json({}));
  }),

  rest.post(`${walletUrl}/v0/wallet/transfer-preapproval`, async (_, res, ctx) => {
    await requestMocks.createTransferPreapproval();
    return res(ctx.json({}));
  }),

  rest.post(`${walletUrl}/v0/wallet/transfer-preapproval/send`, async (req, res, ctx) => {
    await requestMocks.transferPreapprovalSend(await req.json());
    return res(ctx.json({}));
  }),
];
