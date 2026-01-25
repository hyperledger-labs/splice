// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  config,
  DeploySvRunbook,
  GitReferenceSchema,
} from '@lfdecentralizedtrust/splice-pulumi-common';
import {
  mustInstallSplitwell,
  mustInstallValidator1,
} from '@lfdecentralizedtrust/splice-pulumi-common-validator';
import { clusterYamlConfig } from '@lfdecentralizedtrust/splice-pulumi-common/src/config/config';
import { merge } from 'lodash';
import { z } from 'zod';

export const StackConfigSchema = z.object({
  parallelism: z.number().optional(),
});

const ProjectFilterSchema = z.union([
  z.literal('canton-network'),
  z.literal('gha'),
  z.literal('infra'),
  z.literal('multi-validator'),
  z.literal('splitwell'),
  z.literal('sv-canton'),
  z.literal('sv-runbook'),
  // 'validators' would be a better name here, but for the sake of consistency
  // the name of the actual Pulumi project is used.
  z.literal('validator-runbook'),
  z.literal('validator1'),
]);

export type ProjectFilter = z.infer<typeof ProjectFilterSchema>;

function* iterateDefaultProjectFilters(): Generator<ProjectFilter> {
  yield 'canton-network';
  yield 'infra';
  yield 'sv-canton';
  yield 'validator-runbook';

  // TODO(DACH-NY/canton-network-internal#875): Remove the following env. var. based additions when
  //   it is certain that all cases of their usage is replaced with explicit configuration.
  //   Note that their inclusion is purposefully limited to default project filters so that explicit
  //   configuration always takes precedence to maximize clarity.
  if (DeploySvRunbook) {
    yield 'sv-runbook';
  }
  if (config.envFlag('SPLICE_DEPLOY_MULTI_VALIDATOR', false)) {
    yield 'multi-validator';
  }
  if (mustInstallValidator1) {
    yield 'validator1';
  }
  if (mustInstallSplitwell) {
    yield 'splitwell';
  }
}

export const OperatorDeploymentConfigSchema = z.object({
  operatorDeployment: z.object({
    reference: GitReferenceSchema,
  }),
  pulumiStacks: z.record(z.string(), StackConfigSchema).and(
    z.object({
      default: StackConfigSchema,
    })
  ),
  deployment: z
    .object({
      projectsToDeploy: z.array(ProjectFilterSchema).transform(arr => new Set(arr)),
    })
    .default({
      projectsToDeploy: [...iterateDefaultProjectFilters()],
    }),
});

export type Config = z.infer<typeof OperatorDeploymentConfigSchema>;
export type StackConfig = z.infer<typeof StackConfigSchema>;

// eslint-disable-next-line
// @ts-ignore
const fullConfig = OperatorDeploymentConfigSchema.parse(clusterYamlConfig);
export const operatorDeploymentConfig = fullConfig.operatorDeployment;
export const deploymentConf = fullConfig.deployment;

export const PulumiOperatorGracePeriod = 1800;

export const configForStack = (stackName: string): StackConfig => {
  return merge({}, fullConfig.pulumiStacks.default, fullConfig.pulumiStacks[stackName]);
};
