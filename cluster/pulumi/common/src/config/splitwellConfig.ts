import { z } from 'zod';

import { clusterYamlConfig } from './configLoader';

export const SplitwellConfigSchema = z.object({
  splitwell: z
    .object({
      maxDarVersion: z.string().optional(),
    })
    .optional(),
});

export type Config = z.infer<typeof SplitwellConfigSchema>;

export const splitwellConfig = SplitwellConfigSchema.parse(clusterYamlConfig).splitwell;
