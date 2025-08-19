// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { z } from 'zod';

// TODO(DACH-NY/canton-network-node#9049)
const contract = z.object({
  template_id: z.string(),
  contract_id: z.string(),
  created_event_blob: z.string(),
  created_at: z.string(),
});

export const acceptTransferOfferResponse = z.object({});

export type AcceptTransferOfferResponse = z.infer<typeof acceptTransferOfferResponse>;

export const createTransferOfferResponse = z.object({
  output: z.object({
    transfer_instruction_cid: z.string(),
  }),
});

export type CreateTransferOfferResponse = z.infer<typeof createTransferOfferResponse>;

export const getBalanceResponse = z.object({
  round: z.number(),
  effective_unlocked_qty: z.string(),
  effective_locked_qty: z.string(),
  total_holding_fees: z.string(),
});

export type GetBalanceResponse = z.infer<typeof getBalanceResponse>;

const transferOfferContract = contract;

export const listTransferOffersResponse = z.object({
  transfers: z.array(transferOfferContract),
});

export type ListTransferOffersResponse = z.infer<typeof listTransferOffersResponse>;

export const userStatusResponse = z.object({
  party_id: z.string(),
  user_onboarded: z.boolean(),
  user_wallet_installed: z.boolean(),
  has_featured_app_right: z.boolean(),
});

export type UserStatusResponse = z.infer<typeof userStatusResponse>;
