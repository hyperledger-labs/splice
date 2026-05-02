// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import z from "zod";

const dripConfigSchema = z.object({
  participantLedgerApi: z.string().min(1),
  ledgerAccessToken: z.string().min(1),
  validatorApiUrl: z.string().min(1),
  validatorAccessToken: z.string().min(1),
  adminUser: z.string().min(1),
  synchronizerId: z.string().min(1),
  senderPartyId: z.string().min(1),
  instrumentAdmin: z.string().min(1),
  instrumentId: z.string().default("Amulet"),
  dripAmount: z.string().default("1.0"),
  delayBetweenRowsMs: z.coerce.number().default(300),
  retryCount: z.coerce.number().default(2),
  metaReason: z.string().default("Amulet distribution"),
});

export type DripConfig = z.infer<typeof dripConfigSchema>;

export function loadDripConfig(): DripConfig {
  return dripConfigSchema.parse({
    participantLedgerApi: process.env.PARTICIPANT_LEDGER_API,
    ledgerAccessToken: process.env.LEDGER_ACCESS_TOKEN,
    validatorApiUrl: process.env.VALIDATOR_API_URL,
    validatorAccessToken: process.env.VALIDATOR_ACCESS_TOKEN,
    adminUser: process.env.ADMIN_USER ?? "ledger-api-user",
    synchronizerId: process.env.SYNCHRONIZER_ID,
    senderPartyId: process.env.SENDER_PARTY_ID,
    instrumentAdmin: process.env.INSTRUMENT_ADMIN,
    instrumentId: process.env.INSTRUMENT_ID,
    dripAmount: process.env.DRIP_AMOUNT,
    delayBetweenRowsMs: process.env.DRIP_DELAY_MS,
    retryCount: process.env.DRIP_RETRY_COUNT,
    metaReason: process.env.DRIP_META_REASON,
  });
}
