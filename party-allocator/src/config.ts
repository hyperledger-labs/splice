// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import z from "zod";

const partyAllocationsSchema = z.object({
  token: z.string(),
  userId: z.string(),
  jsonLedgerApiUrl: z.string(),
  scanApiUrl: z.string(),
  validatorApiUrl: z.string(),
  maxParties: z.number(),
  keyDirectory: z.string(),
  parallelism: z.number().default(20),
  batchSize: z.number().default(1000),
  preapprovalRetries: z.number().default(120),
  preapprovalRetryDelayMs: z.number().default(1000),
});

type PartyAllocationsConf = z.infer<typeof partyAllocationsSchema>;

if (!process.env.EXTERNAL_CONFIG) {
  throw new Error("EXTERNAL_CONFIG envrionment variable must be set");
}

export const config: PartyAllocationsConf = partyAllocationsSchema.parse(
  JSON.parse(process.env.EXTERNAL_CONFIG!),
);
