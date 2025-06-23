// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { GitReferenceSchema } from 'splice-pulumi-common';
import { clusterYamlConfig } from 'splice-pulumi-common/src/config/configLoader';
import { z } from 'zod';

export const OperatorDeploymentConfigSchema = z.object({
  operatorDeployment: z.object({
    reference: GitReferenceSchema,
    // TODO(DACH-NY/canton-network-internal#544) - delete this and move the config back to the operator project
    useOperatorV2: z.boolean().default(true),
  }),
});

export type Config = z.infer<typeof OperatorDeploymentConfigSchema>;

// eslint-disable-next-line
// @ts-ignore
const fullConfig = OperatorDeploymentConfigSchema.parse(clusterYamlConfig);
export const operatorDeploymentConfig = fullConfig.operatorDeployment;

export const PulumiOperatorGracePeriod = 1800;
