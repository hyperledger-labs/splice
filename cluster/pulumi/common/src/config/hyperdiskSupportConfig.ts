import { z } from 'zod';

import { clusterSubConfig } from './config';

const HyperdiskSupportConfigSchema = z.object({
  hyperdiskSupport: z
    .object({
      enabled: z.boolean().default(false),
    })
    .default({}),
});

export type HyperdiskSupportConfig = z.infer<typeof HyperdiskSupportConfigSchema>;

export const hyperdiskSupportConfig: HyperdiskSupportConfig = HyperdiskSupportConfigSchema.parse(
  clusterSubConfig('cluster')
);
