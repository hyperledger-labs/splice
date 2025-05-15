import util from 'node:util';
import { clusterYamlConfig } from 'splice-pulumi-common/src/config/configLoader';
import { z } from 'zod';

const GhaConfigSchema = z.object({
  gha: z.object({
    githubRepo: z.string(),
  }),
});

export type Config = z.infer<typeof GhaConfigSchema>;

// eslint-disable-next-line
// @ts-ignore
const fullConfig = GhaConfigSchema.parse(clusterYamlConfig);

console.error(
  `Loaded GHA config: ${util.inspect(fullConfig, {
    depth: null,
    maxStringLength: null,
  })}`
);

export const ghaConfig = fullConfig.gha;
