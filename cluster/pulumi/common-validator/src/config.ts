// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  DeployValidatorRunbook,
  KmsConfigSchema,
  LogLevelSchema,
} from '@lfdecentralizedtrust/splice-pulumi-common/src/config';
import { clusterSubConfig } from 'splice-pulumi-common/src/config/configLoader';
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
export const ValidatorConfigSchema = z
  .object({
    namespace: z.string(),
    partyHint: z.string(),
    migrateParty: z.boolean().default(false),
    newParticipantId: z.string().optional(),
    onboardingSecret: z.string().optional(),
    partyAllocator: z
      .object({
        enable: z.boolean(),
      })
      .default({ enable: false }),
  })
  .and(ValidatorNodeConfigSchema);

export const ValidatorsConfigSchema = z.record(z.string(), ValidatorConfigSchema);
export type ValidatorsConfig = z.infer<typeof ValidatorsConfigSchema>;
export type ValidatorConfig = z.infer<typeof ValidatorConfigSchema>;

export const allValidatorsConfig: ValidatorsConfig = ValidatorsConfigSchema.parse(
  clusterSubConfig('validators')
);

const allValidators = Object.keys(allValidatorsConfig);
export const deployedValidators = DeployValidatorRunbook
  ? allValidators
  : allValidators.filter(validator => validator !== 'validator-runbook');
