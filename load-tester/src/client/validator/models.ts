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

const transferOfferPayload = z.object({
  trackingId: z.string(),
  description: z.string(),
  expiresAt: z.string(),
  receiver: z.string(),
  amount: z.object({
    amount: z.string(),
    unit: z.enum(['AmuletUnit', 'USDUnit']),
  }),
  sender: z.string(),
  dso: z.string(),
});

export const acceptTransferOfferResponse = z.object({
  accepted_offer_contract_id: z.string(),
});

export type AcceptTransferOfferResponse = z.infer<typeof acceptTransferOfferResponse>;

export const createTransferOfferResponse = z.object({
  offer_contract_id: z.string(),
});

export type CreateTransferOfferResponse = z.infer<typeof createTransferOfferResponse>;

const partyAndAmount = z.any();

const listTransactionsItem = z.object({
  transaction_type: z.string(),
  transaction_subtype: z.object({
    template_id: z.string(),
    choice: z.string(),
    amulet_operation: z.optional(z.string()),
  }),
  event_id: z.string(),
  offset: z.optional(z.string()),
  domain_id: z.string(),
  date: z.string().datetime(),
  provider: z.optional(z.string()),
  sender: z.optional(partyAndAmount),
  receivers: z.optional(z.array(partyAndAmount)),
  holding_fees: z.optional(z.string()),
  amulet_price: z.optional(z.string()),
  app_rewards_used: z.optional(z.string()),
  validator_rewards_used: z.optional(z.string()),
  details: z.optional(z.string()),
});

export const getBalanceResponse = z.object({
  round: z.number(),
  effective_unlocked_qty: z.string(),
  effective_locked_qty: z.string(),
  total_holding_fees: z.string(),
});

export type GetBalanceResponse = z.infer<typeof getBalanceResponse>;

export const getTransferOfferStatusResponse = z.object({
  status: z.enum(['created', 'accepted', 'completed', 'failed']),
  transaction_id: z.optional(z.string()).nullable(),
  contract_id: z.optional(z.string()),
  failure_kind: z.optional(z.string()).nullable(),
  withdrawn_reason: z.optional(z.string()).nullable(),
});

export type GetTransferOfferStatusResponse = z.infer<typeof getTransferOfferStatusResponse>;

export const listTransactionsResponse = z.object({
  items: z.array(listTransactionsItem),
});

export type ListTransactionsResponse = z.infer<typeof listTransactionsResponse>;

const transferOfferContract = contract.and(z.object({ payload: transferOfferPayload }));

export const listTransferOffersResponse = z.object({
  offers: z.array(transferOfferContract),
});

export type ListTransferOffersResponse = z.infer<typeof listTransferOffersResponse>;

export const userStatusResponse = z.object({
  party_id: z.string(),
  user_onboarded: z.boolean(),
  user_wallet_installed: z.boolean(),
  has_featured_app_right: z.boolean(),
});

export type UserStatusResponse = z.infer<typeof userStatusResponse>;
