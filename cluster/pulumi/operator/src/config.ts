import { clusterYamlConfig } from 'splice-pulumi-common/src/config/configLoader';
import { z } from 'zod';

export const OperatorDeploymentConfigSchema = z.object({
  operatorDeployment: z.object({
    reference: z.string(),
  }),
});

export type Config = z.infer<typeof OperatorDeploymentConfigSchema>;

// eslint-disable-next-line
// @ts-ignore
const fullConfig = OperatorDeploymentConfigSchema.parse(clusterYamlConfig);
export const operatorDeploymentConfig = fullConfig.operatorDeployment;
