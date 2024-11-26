import { z } from 'zod';

import { defaultActiveMigration, SynchronizerMigrationSchema } from './migrationSchema';

export const ConfigSchema = z.object({
  synchronizerMigration: SynchronizerMigrationSchema.default({
    active: defaultActiveMigration,
  }),
  persistentSequencerHeapDumps: z.boolean().default(false),
});

export type Config = z.infer<typeof ConfigSchema>;
