import { z } from 'zod';

import { CHARTS_VERSION, CnChartVersion, parsedVersion } from '../artifacts';
import { spliceEnvConfig } from './envConfig';

export enum MigrationProvider {
  INTERNAL = 'internal',
  EXTERNAL = 'external',
}

export const defaultActiveMigration = {
  id: 0,
  version: CHARTS_VERSION,
  provider: MigrationProvider.INTERNAL,
};

export const MigrationInfoSchema = z
  .object({
    id: z
      .number()
      .lt(10, 'Migration id must be less than or equal to 10 as we use in the cometbft ports.')
      .gte(0),
    version: z
      .string()
      .optional()
      .transform<CnChartVersion>((version, ctx) => {
        if (CHARTS_VERSION && version && CHARTS_VERSION !== version) {
          ctx.addIssue({
            code: z.ZodIssueCode.custom,
            message: `Specified different active version and CHARTS_VERSION: ${version} - ${CHARTS_VERSION}`,
          });
          return z.NEVER;
        } else {
          if (
            !version &&
            !CHARTS_VERSION &&
            spliceEnvConfig.optionalEnv('SPLICE_OPERATOR_DEPLOYMENT')
          ) {
            ctx.addIssue({
              code: z.ZodIssueCode.custom,
              message: `No active version or CHARTS_VERSION specified`,
            });
            return z.NEVER;
          } else {
            return parsedVersion(
              version || CHARTS_VERSION,
              // eslint-disable-next-line no-process-env
              process.env.SPLICE_ARTIFACTS_REPOSITORY
            );
          }
        }
      }),
    provider: z.nativeEnum(MigrationProvider),
    releaseReference: z.string().optional(),
  })
  .strict();

const NonActiveMigrationSchema = MigrationInfoSchema.refine(info => {
  return info.provider === MigrationProvider.EXTERNAL || info.releaseReference === undefined;
}, 'For internal migrations an external reference cannot be provided, the active reference is used.').optional();

export const SynchronizerMigrationSchema = z
  .object({
    legacy: NonActiveMigrationSchema,
    active: MigrationInfoSchema.extend({
      migratingFrom: z.number().optional(),
    })
      .strict()
      .default(defaultActiveMigration),
    upgrade: NonActiveMigrationSchema,
    archived: z.array(MigrationInfoSchema).optional(),
    activeDatabaseId: z.number().optional(),
  })
  .strict();
