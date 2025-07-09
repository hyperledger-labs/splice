// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';
import util from 'node:util';
import {
  config,
  loadJsonFromFile,
  PRIVATE_CONFIGS_PATH,
  clusterDirectory,
} from 'splice-pulumi-common';
import { spliceConfig } from 'splice-pulumi-common/src/config/config';
import { clusterYamlConfig } from 'splice-pulumi-common/src/config/configLoader';
import { z } from 'zod';

export const gcpProjectId = pulumi.getStack();

const GCP_PROJECT = config.requireEnv('CLOUDSDK_CORE_PROJECT');
if (!GCP_PROJECT) {
  throw new Error('CLOUDSDK_CORE_PROJECT is undefined');
}
if (gcpProjectId !== GCP_PROJECT) {
  throw new Error(
    `The stack name (${gcpProjectId}) does not match CLOUDSDK_CORE_PROJECT (${GCP_PROJECT}) -- check your environment or active stack`
  );
}

export const GcpProjectConfigSchema = z.object({
  authorizedServiceAccountEmail: z.string().optional(),
});

export type Config = z.infer<typeof GcpProjectConfigSchema>;

// eslint-disable-next-line
// @ts-ignore
const fullConfig = InfraConfigSchema.parse(clusterYamlConfig);

console.error(
  `Loaded infra config: ${util.inspect(fullConfig, {
    depth: null,
    maxStringLength: null,
  })}`
);
