// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { z } from 'zod';

import { clusterSubConfig } from './config';

const HyperdiskSupportConfigSchema = z.object({
  hyperdiskSupport: z
    .object({
      enabled: z.boolean(),
      enabledForInfra: z.boolean(),
      migrating: z.boolean().default(false),
      migratingInfra: z.boolean().default(false),
    })
    .strict(),
});

export type HyperdiskSupportConfig = z.infer<typeof HyperdiskSupportConfigSchema>;

export const hyperdiskSupportConfig: HyperdiskSupportConfig = HyperdiskSupportConfigSchema.parse(
  clusterSubConfig('cluster')
);
