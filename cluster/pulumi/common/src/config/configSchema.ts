import { Validator1ConfigSchema } from 'validator1/src/config';
import { z } from 'zod';

import { defaultActiveMigration, SynchronizerMigrationSchema } from './migrationSchema';

export const ConfigSchema = z.object({
  synchronizerMigration: SynchronizerMigrationSchema.default({
    active: defaultActiveMigration,
  }),
  persistentSequencerHeapDumps: z.boolean().default(false),
  validator1: Validator1ConfigSchema.optional(),
});

export type Config = z.infer<typeof ConfigSchema>;
