// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { KmsConfigSchema, LogLevelSchema } from '@lfdecentralizedtrust/splice-pulumi-common';
import { ValidatorAppConfigSchema } from '@lfdecentralizedtrust/splice-pulumi-common-validator/src/config';
import { clusterYamlConfig } from '@lfdecentralizedtrust/splice-pulumi-common/src/config/configLoader';
import { merge } from 'lodash';
import util from 'node:util';
import { z } from 'zod';

const SvCometbftConfigSchema = z
  .object({
    nodeId: z.string().optional(),
    validatorKeyAddress: z.string().optional(),
    // defaults to {svName}-cometbft-keys if not set
    keysGcpSecret: z.string().optional(),
    snapshotName: z.string().optional(),
  })
  .strict();
const EnvVarConfigSchema = z.object({
  name: z.string(),
  value: z.string(),
});
const SvSequencerConfigSchema = z
  .object({
    additionalEnvVars: z.array(EnvVarConfigSchema).default([]),
    additionalJvmOptions: z.string().optional(),
  })
  .strict();
const SvParticipantConfigSchema = z
  .object({
    kms: KmsConfigSchema.optional(),
    bftSequencerConnection: z.boolean().optional(),
    additionalEnvVars: z.array(EnvVarConfigSchema).default([]),
    additionalJvmOptions: z.string().optional(),
  })
  .strict();
const Auth0ConfigSchema = z
  .object({
    name: z.string().optional(),
    clientId: z.string().optional(),
  })
  .strict();
const SvAppConfigSchema = z
  .object({
    additionalEnvVars: z.array(EnvVarConfigSchema).default([]),
    additionalJvmOptions: z.string().optional(),
    auth0: Auth0ConfigSchema.optional(),
    // defaults to {svName}-id if not set
    svIdKeyGcpSecret: z.string().optional(),
    // defaults to {svName}-cometbft-governance-key if not set
    cometBftGovernanceKeyGcpSecret: z.string().optional(),
  })
  .strict();
const ScanAppConfigSchema = z
  .object({
    bigQuery: z
      .object({
        dataset: z.string(),
        prefix: z.string(),
      })
      .optional(),
    additionalEnvVars: z.array(EnvVarConfigSchema).default([]),
    additionalJvmOptions: z.string().optional(),
  })
  .strict();
const SvValidatorAppConfigSchema = z
  .object({
    walletUser: z.string().optional(),
    // TODO(#2389) inline env var into config.yaml
    sweep: z
      .object({
        fromEnv: z.string(),
      })
      .optional(),
    auth0: Auth0ConfigSchema.optional(),
  })
  .and(ValidatorAppConfigSchema);
// https://docs.cometbft.com/main/explanation/core/running-in-production
const CometbftLogLevelSchema = z.enum(['info', 'error', 'debug', 'none']);
// things here are declared optional even when they aren't, to allow partial overrides of defaults
const SingleSvConfigSchema = z
  .object({
    publicName: z.string().optional(),
    subdomain: z.string().optional(),
    cometbft: SvCometbftConfigSchema.optional(),
    participant: SvParticipantConfigSchema.optional(),
    sequencer: SvSequencerConfigSchema.optional(),
    svApp: SvAppConfigSchema.optional(),
    scanApp: ScanAppConfigSchema.optional(),
    validatorApp: SvValidatorAppConfigSchema.optional(),
    logging: z
      .object({
        appsLogLevel: LogLevelSchema,
        cantonLogLevel: LogLevelSchema,
        cantonStdoutLogLevel: LogLevelSchema.optional(),
        cometbftLogLevel: CometbftLogLevelSchema.optional(),
        cometbftExtraLogLevelFlags: z.string().optional(),
      })
      .optional(),
  })
  .strict();
const AllSvsConfigurationSchema = z.record(z.string(), SingleSvConfigSchema).and(
  z.object({
    default: SingleSvConfigSchema,
  })
);
const SvsConfigurationSchema = z.object({
  svs: AllSvsConfigurationSchema,
});

type SingleSvConfig = z.infer<typeof AllSvsConfigurationSchema>;
export type SingleSvConfiguration = z.infer<typeof SingleSvConfigSchema>;

const clusterSvsConfiguration: SingleSvConfig = SvsConfigurationSchema.parse(clusterYamlConfig).svs;

export const allConfiguredSvs: string[] = Object.keys(clusterSvsConfiguration).filter(
  k => k !== 'default'
);

// SVs that don't match the standard sv-X pattern; we deploy those always, independently of DSO_SIZE
export const configuredExtraSvs: string[] = allConfiguredSvs.filter(k => !k.match(/^sv(-\d+)?$/));

export const configForSv = (svName: string): SingleSvConfiguration => {
  return merge({}, clusterSvsConfiguration.default, clusterSvsConfiguration[svName]);
};

console.error(
  'Loaded SVS configuration',
  util.inspect(clusterSvsConfiguration, {
    depth: null,
    maxStringLength: null,
  })
);
