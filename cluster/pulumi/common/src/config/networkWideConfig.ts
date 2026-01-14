// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { clusterYamlConfig } from '@lfdecentralizedtrust/splice-pulumi-common/src/config/config';
import { z } from 'zod';

// Network wide configuration expected to be shared by all nodes.
export const NetworkWideConfigSchema = z.object({
  networkWide: z
    .object({
      maxVettingDelay: z.string().optional(),
    })
    .optional(),
});

export type NetworkWideConfig = z.infer<typeof NetworkWideConfigSchema>;

export const networkWideConfig = NetworkWideConfigSchema.parse(clusterYamlConfig).networkWide;
