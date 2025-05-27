import { clusterYamlConfig } from 'splice-pulumi-common/src/config/configLoader';
import { z } from 'zod';

export const LoadTesterConfigSchema = z.object({
  loadTester: z
    .object({
      enable: z.boolean(),
      minRate: z.number().default(0.9),
      iterationsPerMinute: z.number().default(60),
    })
    .optional(),
});

export type LoadTesterConfig = z.infer<typeof LoadTesterConfigSchema>;

export const loadTesterConfig = LoadTesterConfigSchema.parse(clusterYamlConfig).loadTester;

console.error(`LOADTESTER ${JSON.stringify(loadTesterConfig)}`);
