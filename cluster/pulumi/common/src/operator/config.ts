// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { GitReferenceSchema } from '@lfdecentralizedtrust/splice-pulumi-common';
import { clusterYamlConfig } from '@lfdecentralizedtrust/splice-pulumi-common/src/config/config';
import { merge } from 'lodash';
import { z } from 'zod';

export const StackConfigSchema = z.object({
  parallelism: z.number().optional(),
});

export const OperatorDeploymentConfigSchema = z.object({
  operatorDeployment: z.object({
    reference: GitReferenceSchema,
  }),
  pulumiStacks: z.record(z.string(), StackConfigSchema).and(
    z.object({
      default: StackConfigSchema,
    })
  ),
});

export type Config = z.infer<typeof OperatorDeploymentConfigSchema>;
export type StackConfig = z.infer<typeof StackConfigSchema>;

// eslint-disable-next-line
// @ts-ignore
const fullConfig = OperatorDeploymentConfigSchema.parse(clusterYamlConfig);
export const operatorDeploymentConfig = fullConfig.operatorDeployment;

export const PulumiOperatorGracePeriod = 1800;

export const configForStack = (stackName: string): StackConfig => {
  return merge({}, fullConfig.pulumiStacks.default, fullConfig.pulumiStacks[stackName]);
};
