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

export const ScanClientConfigSchema = z.object({
  scanType: z.enum(['trust-single', 'bft', 'bft-custom']),
  scanAddress: z.string().optional(), // redundant with seedUrls
  threshold: z.number().default(0),
  trustedSvs: z.array(z.string()).default([]),
  seedUrls: z.array(z.string()).optional(),
});

export const ValidatorAppConfigSchema = z.object({
  additionalEnvVars: z.array(EnvVarConfigSchema).default([]),
  additionalJvmOptions: z.string().optional(),
  scanClient: ScanClientConfigSchema.optional(),
});

export const ParticipantConfigSchema = z.object({
  additionalEnvVars: z.array(EnvVarConfigSchema).default([]),
  additionalJvmOptions: z.string().optional(),
});

export const ValidatorNodeConfigSchema = z.object({
  logging: z
    .object({
      level: LogLevelSchema.optional(),
      async: z.boolean().optional(),
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
  validatorApp: ValidatorAppConfigSchema.optional(),
  disableAuth: z.boolean().default(false), // Note that this is currently ignored everywhere except for validator1, where it is used for testing only
});
export const PartyAllocatorConfigSchema = z.object({
  enable: z.boolean(),
  parallelism: z.number().default(30),
  maxParties: z.number().default(1000000),
  preapprovalRetries: z.number().default(120),
  preapprovalRetryDelayMs: z.number().default(1000),
});
export type PartyAllocatorConfig = z.infer<typeof PartyAllocatorConfigSchema>;

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
    partyAllocator: PartyAllocatorConfigSchema.default({ enable: false }),
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
