// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { KmsConfigSchema, LogLevelSchema } from '@lfdecentralizedtrust/splice-pulumi-common';
import { z } from 'zod';

export const ValidatorNodeConfigSchema = z.object({
  logging: z
    .object({
      level: LogLevelSchema.optional(),
    })
    .default({}),
  kms: KmsConfigSchema.optional(),
  participantPruningSchedule: z
    .object({
      cron: z.string(),
      maxDuration: z.string(),
      retention: z.string(),
    })
    .optional(),
});
export type ValidatorNodeConfig = z.infer<typeof ValidatorNodeConfigSchema>;
