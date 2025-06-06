import * as _ from 'lodash';
import * as util from 'node:util';
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
  provider: MigrationProvider.EXTERNAL,
};

const migrationVersion = z
  .string()
  .optional()
  .transform<CnChartVersion>((version, ctx) => {
    if (!version && !CHARTS_VERSION && spliceEnvConfig.optionalEnv('SPLICE_OPERATOR_DEPLOYMENT')) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        message: `No active version or CHARTS_VERSION specified`,
      });
      return z.NEVER;
    } else {
      return parsedVersion(version || CHARTS_VERSION);
    }
  });

export const GitReferenceSchema = z.object({
  repoUrl: z.string(),
  gitReference: z.string(),
  // All directory paths are relative to the root of the repo pointed to by repoUrl
  pulumiStacksDir: z.string(),
  pulumiBaseDir: z.string(),
  deploymentDir: z.string(),
  spliceRoot: z.string(), // (use "." if checking out splice directly)
  privateConfigsDir: z.string().optional(),
  publicConfigsDir: z.string().optional(),
});

export const MigrationInfoSchema = z
  .object({
    id: z
      .number()
      .lt(10, 'Migration id must be less than or equal to 10 as we use in the cometbft ports.')
      .gte(0),
    version: migrationVersion,
    provider: z.nativeEnum(MigrationProvider).default(MigrationProvider.EXTERNAL),
    releaseReference: GitReferenceSchema.optional(),
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
      version: migrationVersion.transform((version, ctx) => {
        const parsedChartsVersion = parsedVersion(CHARTS_VERSION);
        if (CHARTS_VERSION && !_.isEqual(parsedChartsVersion, version)) {
          ctx.addIssue({
            code: z.ZodIssueCode.custom,
            message: `Specified different active version and CHARTS_VERSION: ${util.inspect(version)} - ${util.inspect(parsedChartsVersion)}`,
          });
          return z.NEVER;
        } else {
          return version;
        }
      }),
    })
      .strict()
      .default(defaultActiveMigration),
    upgrade: NonActiveMigrationSchema,
    archived: z.array(MigrationInfoSchema).optional(),
    activeDatabaseId: z.number().optional(),
  })
  .strict();
