import util from 'node:util';
import { clusterYamlConfig } from 'splice-pulumi-common/src/config/configLoader';
import { z } from 'zod';

const SvCometbftConfigSchema = z
  .object({
    snapshotName: z.string(),
  })
  .optional();
const SingleSvConfigSchema = z.object({
  cometbft: SvCometbftConfigSchema,
});
const AllSvsConfigurationSchema = z.record(z.string(), SingleSvConfigSchema);
const SvsConfigurationSchema = z
  .object({
    svs: AllSvsConfigurationSchema.optional().default({}),
  })
  .optional()
  .default({});

type SvsConfiguration = z.infer<typeof AllSvsConfigurationSchema>;

export const svsConfiguration: SvsConfiguration =
  SvsConfigurationSchema.parse(clusterYamlConfig).svs;

console.error(
  'Loaded SVS configuration',
  util.inspect(svsConfiguration, {
    depth: null,
    maxStringLength: null,
  })
);
