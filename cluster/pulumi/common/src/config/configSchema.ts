import { z } from 'zod';

import { defaultActiveMigration, SynchronizerMigrationSchema } from './migrationSchema';

const PulumiProjectConfigSchema = z.object({
  installDataOnly: z.boolean(),
  allowedArtifactories: z.array(z.enum(['public', 'private'])),
});
export type PulumiProjectConfig = z.infer<typeof PulumiProjectConfigSchema>;
export const ConfigSchema = z.object({
  synchronizerMigration: SynchronizerMigrationSchema.default({
    active: defaultActiveMigration,
  }),
  persistentSequencerHeapDumps: z.boolean().default(false),
  pulumiProjectConfig: z
    .object({
      default: PulumiProjectConfigSchema,
    })
    .and(z.record(PulumiProjectConfigSchema)),
});

export type Config = z.infer<typeof ConfigSchema>;
