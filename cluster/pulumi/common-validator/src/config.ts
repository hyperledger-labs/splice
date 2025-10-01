// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  DeployValidatorRunbook,
  EnvVarConfigSchema,
  KmsConfigSchema,
  LogLevelSchema,
} from '@lfdecentralizedtrust/splice-pulumi-common/src/config';
import { clusterSubConfig } from '@lfdecentralizedtrust/splice-pulumi-common/src/config/configLoader';
import { z } from 'zod';

export const ParticipantConfigSchema = z.object({
  additionalEnvVars: z.array(EnvVarConfigSchema).default([]),
  additionalJvmOptions: z.string().optional(),
});

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
  participant: ParticipantConfigSchema.optional(),
});
export type ValidatorNodeConfig = z.infer<typeof ValidatorNodeConfigSchema>;
export const ValidatorConfigSchema = z
  .object({
    namespace: z.string(),
    partyHint: z.string(),
    nodeIdentifier: z.string().optional(),
    // Default to admin@validator.com at the validator-test tenant by default
    operatorWalletUserId: z.string().default('auth0|6526fab5214c99a9a8e1e3cc'),
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

export const validatorRunbookStackName = (name: string): string =>
  name === 'validator-runbook' ? name : `validators.${name}`;
