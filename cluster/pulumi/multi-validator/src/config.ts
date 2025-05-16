import { clusterYamlConfig } from 'splice-pulumi-common/src/config/configLoader';
import { z } from 'zod';

export const MultiValidatorConfigSchema = z.object({
  multiValidator: z
    .object({
      postgresPvcSize: z.string(),
    })
    .optional(),
});

export type Config = z.infer<typeof MultiValidatorConfigSchema>;

// eslint-disable-next-line
// @ts-ignore
export const multiValidatorConfig =
  MultiValidatorConfigSchema.parse(clusterYamlConfig).multiValidator;
