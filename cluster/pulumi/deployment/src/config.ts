// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { config, DeploySvRunbook } from '@lfdecentralizedtrust/splice-pulumi-common';
import {
  mustInstallSplitwell,
  mustInstallValidator1,
} from '@lfdecentralizedtrust/splice-pulumi-common-validator';
import { clusterYamlConfig } from '@lfdecentralizedtrust/splice-pulumi-common/src/config/configLoader';
import { z } from 'zod';

const ProjectFilterSchema = z.union([
  z.literal('canton-network'),
  z.literal('gha'),
  z.literal('infra'),
  z.literal('multi-validator'),
  z.literal('splitwell'),
  z.literal('sv-canton'),
  z.literal('sv-runbook'),
  z.literal('validator-runbook'),
  z.literal('validator1'),
]);

export type ProjectFilter = z.infer<typeof ProjectFilterSchema>;

const defaultProjectFilters = [
  'canton-network',
  'infra',
  'sv-canton',
  'validator-runbook',
] as const;

const DeploymentConfigSchema = z.object({
  deployment: z
    .object({
      projectWhitelist: z.set(ProjectFilterSchema),
    })
    .default({
      projectWhitelist: new Set(defaultProjectFilters),
    }),
});

export const deploymentConf = DeploymentConfigSchema.parse(clusterYamlConfig).deployment;

// TODO(DACH-NY/canton-network-internal#875): Remove the following env. var. based additions when
//   it is certain that all cases of their usage is replaced with explicit configuration.
if (DeploySvRunbook) {
  deploymentConf.projectWhitelist.add('sv-runbook');
}
if (config.envFlag('SPLICE_DEPLOY_MULTI_VALIDATOR', false)) {
  deploymentConf.projectWhitelist.add('multi-validator');
}
if (mustInstallValidator1) {
  deploymentConf.projectWhitelist.add('validator1');
}
if (mustInstallSplitwell) {
  deploymentConf.projectWhitelist.add('splitwell');
}
