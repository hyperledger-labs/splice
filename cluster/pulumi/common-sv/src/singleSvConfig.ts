// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { KmsConfigSchema, LogLevelSchema } from '@lfdecentralizedtrust/splice-pulumi-common';
import { clusterYamlConfig } from '@lfdecentralizedtrust/splice-pulumi-common/src/config/configLoader';
import { merge } from 'lodash';
import util from 'node:util';
import { z } from 'zod';

const SvCometbftConfigSchema = z.object({
  snapshotName: z.string(),
});
const EnvVarConfigSchema = z.object({
  name: z.string(),
  value: z.string(),
});

const SvParticipantConfigSchema = z.object({
  kms: KmsConfigSchema.optional(),
  bftSequencerConnection: z.boolean().default(true),
  additionalEnvVars: z.array(EnvVarConfigSchema).default([]),
});
const SvAppConfigSchema = z.object({
  additionalEnvVars: z.array(EnvVarConfigSchema).default([]),
});
// https://docs.cometbft.com/main/explanation/core/running-in-production
const CometbftLogLevelSchema = z.enum(['info', 'error', 'debug', 'none']);
const SingleSvConfigSchema = z
  .object({
    cometbft: SvCometbftConfigSchema.optional(),
    participant: SvParticipantConfigSchema.optional(),
    svApp: SvAppConfigSchema.optional(),
    logging: z
      .object({
        appsLogLevel: LogLevelSchema,
        cantonLogLevel: LogLevelSchema,
        cometbftLogLevel: CometbftLogLevelSchema.optional(),
        cometbftExtraLogLevelFlags: z.string().optional(),
      })
      .optional(),
    additionalEnvVars: z.array(EnvVarConfigSchema).default([]),
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
