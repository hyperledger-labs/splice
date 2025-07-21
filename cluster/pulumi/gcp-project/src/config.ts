// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';
import util from 'node:util';
import { config } from 'splice-pulumi-common';
import { readAndParseYaml } from 'splice-pulumi-common/src/config/configLoader';
import { z } from 'zod';

export const configsDir = config.requireEnv('GCP_PROJECT_CONFIGS_DIR');

const GcpProjectConfigSchema = z.object({
  ips: z.object({
    'All Clusters': z.array(z.string()),
    'Non-MainNet': z.array(z.string()).default([]),
  }),
  letsEncrypt: z.object({
    email: z.string(),
  }),
  authorizedServiceAccount: z.object({
    email: z.string(),
  }),
});

// eslint-disable-next-line
// @ts-ignore
export const gcpProjectConfig = GcpProjectConfigSchema.parse(readAndParseYaml(`${configsDir}/config.yaml`));

console.error(
  `Loaded gcp-project config: ${util.inspect(gcpProjectConfig, {
    depth: null,
    maxStringLength: null,
  })}`
);
