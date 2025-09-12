import { ValidatorNodeConfigSchema } from '@lfdecentralizedtrust/splice-pulumi-common-validator';
import util from 'node:util';
import { config } from 'splice-pulumi-common';
import { clusterSubConfig } from 'splice-pulumi-common/src/config/configLoader';
import { z } from 'zod';

const ValidatorConfigSchema = z
  .object({
    namespace: z.string(),
    partyHint: z.string(),
    migrateParty: z.boolean().default(false),
    newParticipantId: z.string().optional(),
    onboardingSecret: z.string().optional(),
    partyAllocator: z
      .object({
        enable: z.boolean(),
      })
      .default({ enable: false }),
  })
  .and(ValidatorNodeConfigSchema);

const ValidatorsConfigSchema = z.record(z.string(), ValidatorConfigSchema);
type ValidatorsConfig = z.infer<typeof ValidatorsConfigSchema>;
type ValidatorConfig = z.infer<typeof ValidatorConfigSchema>;

const allValidatorsConfig: ValidatorsConfig = ValidatorsConfigSchema.parse(
  clusterSubConfig('validators')
);

function getValidatorConfig(validatorName: string): ValidatorConfig {
  const config = allValidatorsConfig[validatorName] as ValidatorConfig;
  if (!config) {
    throw new Error(`No configuration found for validator ${validatorName}`);
  }
  return config;
}

const validatorName = config.requireEnv('SPLICE_VALIDATOR_RUNBOOK_VALIDATOR_NAME');
export const validatorConfig = getValidatorConfig(validatorName);

console.error(
  `Loaded validator ${validatorConfig} configuration`,
  util.inspect(validatorConfig, {
    depth: null,
    maxStringLength: null,
  })
);
