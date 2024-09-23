import { z } from 'zod';

import { CHARTS_VERSION, CnChartVersion, parsedVersion } from '../artifacts';

export enum MigrationProvider {
  INTERNAL = 'internal',
  EXTERNAL = 'external',
}

export const defaultActiveMigration = {
  id: 0,
  version: CHARTS_VERSION,
  provider: MigrationProvider.INTERNAL,
};

export const MigrationInfoSchema = z.object({
  id: z
    .number()
    .lt(10, 'Migration id must be less than or equal to 10 as we use in the cometbft ports.')
    .gte(0),
  version: z
    .string()
    .default(CHARTS_VERSION || '')
    .transform<CnChartVersion>(version => {
      // eslint-disable-next-line no-process-env
      return parsedVersion(version, process.env.SPLICE_ARTIFACTS_REPOSITORY);
    }),
  provider: z.nativeEnum(MigrationProvider),
});

export const SynchronizerMigrationSchema = z.object({
  legacy: MigrationInfoSchema.optional(),
  active: MigrationInfoSchema.extend({
    migratingFrom: z.number().optional(),
  }).default(defaultActiveMigration),
  upgrade: MigrationInfoSchema.optional(),
  archived: z.array(MigrationInfoSchema).optional(),
  activeDatabaseId: z.number().optional(),
});
