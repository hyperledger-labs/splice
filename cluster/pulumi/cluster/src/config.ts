// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { clusterSubConfig } from '@lfdecentralizedtrust/splice-pulumi-common/src/config/config';
import { z } from 'zod';

const GkeNodePoolConfigSchema = z.object({
  minNodes: z.number(),
  maxNodes: z.number(),
  nodeType: z.string(),
});
const GkeClusterConfigSchema = z.object({
  nodePools: z.object({
    infra: GkeNodePoolConfigSchema,
    apps: GkeNodePoolConfigSchema,
    hyperdiskApps: GkeNodePoolConfigSchema.optional(),
  }),
});

export type GkeClusterConfig = z.infer<typeof GkeClusterConfigSchema>;
export type GkeNodePoolConfig = z.infer<typeof GkeNodePoolConfigSchema>;

export const gkeClusterConfig: GkeClusterConfig = GkeClusterConfigSchema.parse(
  clusterSubConfig('cluster')
);
