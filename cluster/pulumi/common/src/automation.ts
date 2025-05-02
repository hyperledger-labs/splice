import { clusterYamlConfig } from 'splice-pulumi-common/src/config/configLoader';
import { z } from 'zod';

export const AutomationSchema = z.object({
  automation: z
    .object({
      delegatelessAutomation: z.boolean().default(false),
      expectedTaskDuration: z.number().default(5000),
    })
    .optional()
    .default({ delegatelessAutomation: false, expectedTaskDuration: 5000 }),
});

export type Config = z.infer<typeof AutomationSchema>;

// eslint-disable-next-line
// @ts-ignore
const fullConfig = AutomationSchema.parse(clusterYamlConfig);
export const delegatelessAutomation = fullConfig.automation.delegatelessAutomation;
export const expectedTaskDuration = fullConfig.automation.expectedTaskDuration;
