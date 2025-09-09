// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { KmsConfigSchema, LogLevelSchema } from '@lfdecentralizedtrust/splice-pulumi-common';
import { clusterYamlConfig } from '@lfdecentralizedtrust/splice-pulumi-common/src/config/configLoader';
import { merge } from 'lodash';
import util from 'node:util';
import { z } from 'zod';

const SvCometbftConfigSchema = z
  .object({
    nodeId: z.string().optional(),
    validatorKeyAddress: z.string().optional(),
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
  })
  .strict();
const SvParticipantConfigSchema = z
  .object({
    kms: KmsConfigSchema.optional(),
    bftSequencerConnection: z.boolean().optional(),
    additionalEnvVars: z.array(EnvVarConfigSchema).default([]),
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
    // TODO(tech-debt) inline env var into config.yaml
    sweep: z
      .object({
        fromEnv: z.string(),
      })
      .optional(),
    additionalEnvVars: z.array(EnvVarConfigSchema).default([]),
    auth0: Auth0ConfigSchema.optional(),
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
  })
  .strict();
const ValidatorAppConfigSchema = z
  .object({
    walletUser: z.string().optional(),
    additionalEnvVars: z.array(EnvVarConfigSchema).default([]),
    auth0: Auth0ConfigSchema.optional(),
  })
  .strict();
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
    validatorApp: ValidatorAppConfigSchema.optional(),
    logging: z
      .object({
        appsLogLevel: LogLevelSchema,
        cantonLogLevel: LogLevelSchema,
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

export const allConfiguredSvs = Object.keys(clusterSvsConfiguration).filter(k => k !== 'default');

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
