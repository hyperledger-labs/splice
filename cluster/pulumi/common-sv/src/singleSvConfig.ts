// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { merge } from 'lodash';
import util from 'node:util';
import { KmsConfigSchema, LogLevelSchema } from 'splice-pulumi-common';
import { clusterYamlConfig } from 'splice-pulumi-common/src/config/configLoader';
import { z } from 'zod';

const SvCometbftConfigSchema = z.object({
  snapshotName: z.string(),
});
const SvParticipantConfigSchema = z.object({
  kms: KmsConfigSchema.optional(),
});
// https://docs.cometbft.com/main/explanation/core/running-in-production
const CometbftLogLevelSchema = z.enum(['info', 'error', 'debug', 'none']);
const SingleSvConfigSchema = z.object({
  cometbft: SvCometbftConfigSchema.optional(),
  participant: SvParticipantConfigSchema.optional(),
  logging: z
    .object({
      appsLogLevel: LogLevelSchema,
      cantonLogLevel: LogLevelSchema,
      cometbftLogLevel: CometbftLogLevelSchema.optional(),
      cometbftExtraLogLevelFlags: z.string().optional(),
    })
    .optional(),
});
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
