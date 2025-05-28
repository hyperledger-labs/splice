// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import util from 'node:util';
import { KmsConfigSchema } from 'splice-pulumi-common';
import { clusterYamlConfig } from 'splice-pulumi-common/src/config/configLoader';
import { z } from 'zod';

const SvCometbftConfigSchema = z.object({
  snapshotName: z.string(),
});
const SvParticipantConfigSchema = z.object({
  kms: KmsConfigSchema.optional(),
});
const SingleSvConfigSchema = z.object({
  cometbft: SvCometbftConfigSchema.optional(),
  participant: SvParticipantConfigSchema.optional(),
});
const AllSvsConfigurationSchema = z.record(z.string(), SingleSvConfigSchema);
const SvsConfigurationSchema = z
  .object({
    svs: AllSvsConfigurationSchema.optional().default({}),
  })
  .optional()
  .default({});

type SvsConfiguration = z.infer<typeof AllSvsConfigurationSchema>;

export const clusterSvsConfiguration: SvsConfiguration =
  SvsConfigurationSchema.parse(clusterYamlConfig).svs;

console.error(
  'Loaded SVS configuration',
  util.inspect(clusterSvsConfiguration, {
    depth: null,
    maxStringLength: null,
  })
);
