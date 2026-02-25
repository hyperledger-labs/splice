// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { clusterYamlConfig } from '@lfdecentralizedtrust/splice-pulumi-common/src/config/config';
import util from 'node:util';
import { z } from 'zod';

const GhaConfigSchema = z.object({
    namespace: z.string(),
    gcpSecretName: z.string(),
    githubRepo: z.string(),
    // these a Splice versions
    runnerVersion: z.string(),
    runnerHookVersion: z.string(),
    // this is a https://github.com/actions/actions-runner-controller version
    runnerScaleSetVersion: z.string(),
  })

const ConfigSchema = z.object({
  gha: z.array(GhaConfigSchema),
});

export type GhaConfig = z.infer<typeof GhaConfigSchema>;
export type Config = z.infer<typeof ConfigSchema>;

// eslint-disable-next-line
// @ts-ignore
const fullConfig = ConfigSchema.parse(clusterYamlConfig);

console.error(
  `Loaded GHA config: ${util.inspect(fullConfig, {
    depth: null,
    maxStringLength: null,
  })}`
);

export const ghaConfigs = fullConfig.gha;
