import { clusterYamlConfig } from 'splice-pulumi-common/src/config/configLoader';
import { z } from 'zod';

export const AutomationSchema = z.object({
  automation: z
    .object({
      delegatelessAutomation: z.boolean().default(false),
    })
    .optional()
    .default({ delegatelessAutomation: false }),
});

export type Config = z.infer<typeof AutomationSchema>;

// eslint-disable-next-line
// @ts-ignore
const fullConfig = AutomationSchema.parse(clusterYamlConfig);
export const delegatelessAutomation = fullConfig.automation.delegatelessAutomation;
