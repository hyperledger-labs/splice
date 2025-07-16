import { KmsConfigSchema, LogLevelSchema } from 'splice-pulumi-common';
import { z } from 'zod';

console.error(KmsConfigSchema);

export const ValidatorNodeConfigSchema = z.object({
  logging: z
    .object({
      level: LogLevelSchema.optional(),
    })
    .default({}),
  kms: KmsConfigSchema.optional(),
  participantPruningSchedule: z
    .object({
      cron: z.string(),
      maxDuration: z.string(),
      retention: z.string(),
    })
    .optional(),
});
export type ValidatorNodeConfig = z.infer<typeof ValidatorNodeConfigSchema>;
