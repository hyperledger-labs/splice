import { z } from 'zod';

import { defaultActiveMigration, SynchronizerMigrationSchema } from './migrationSchema';

const PulumiProjectConfigSchema = z.object({
  installDataOnly: z.boolean().default(false),
});
export type PulumiProjectConfig = z.infer<typeof PulumiProjectConfigSchema>;
export const ConfigSchema = z.object({
  synchronizerMigration: SynchronizerMigrationSchema.default({
    active: defaultActiveMigration,
  }),
  persistentSequencerHeapDumps: z.boolean().default(false),
  pulumiProjectConfig: z
    .record(z.string(), PulumiProjectConfigSchema)
    .and(
      z.object({
        default: PulumiProjectConfigSchema,
      })
    )
    .default({ default: {} }),
});

export type Config = z.infer<typeof ConfigSchema>;
