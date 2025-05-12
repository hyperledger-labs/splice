import { z } from 'zod';

import { defaultActiveMigration, SynchronizerMigrationSchema } from './migrationSchema';

const PulumiProjectConfigSchema = z.object({
  installDataOnly: z.boolean(),
  isExternalCluster: z.boolean(),
  hasPublicDocs: z.boolean(),
  interAppsDependencies: z.boolean(),
  cloudSql: z.object({
    enabled: z.boolean(),
    // Docs on cloudsql maintenance windows: https://cloud.google.com/sql/docs/postgres/set-maintenance-window
    maintenanceWindow: z
      .object({
        day: z.number().min(1).max(7).default(2), // 1 (Monday) to 7 (Sunday)
        hour: z.number().min(0).max(23).default(8), // 24-hour format UTC
      })
      .default({ day: 2, hour: 8 }),
    protected: z.boolean(),
    tier: z.string(),
    enterprisePlus: z.boolean(),
  }),
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
    .and(z.record(PulumiProjectConfigSchema.deepPartial())),
});

export type Config = z.infer<typeof ConfigSchema>;
