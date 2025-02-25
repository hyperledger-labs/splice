import { clusterYamlConfig } from 'splice-pulumi-common/src/config/configLoader';
import { z } from 'zod';

export const OperatorConfigSchema = z.object({
  operator: z
    .object({
      destroyNonInfraStacksOnFinalize: z.boolean().default(false),
    })
    .optional(),
});

export type Config = z.infer<typeof OperatorConfigSchema>;

// eslint-disable-next-line
// @ts-ignore
export const operatorConfig = OperatorConfigSchema.parse(clusterYamlConfig).operator;
