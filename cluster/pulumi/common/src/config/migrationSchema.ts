// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { z } from 'zod';

import { CnChartVersion, parsedVersion } from '../artifacts';
import { spliceEnvConfig } from './envConfig';

export const defaultActiveMigration = {
  id: 0,
  version: 'local',
  sequencer: {
    enableBftSequencer: false,
  },
};

const migrationVersion = z.string().transform<CnChartVersion>((version, ctx) => {
  if (version == 'local' && spliceEnvConfig.optionalEnv('SPLICE_OPERATOR_DEPLOYMENT')) {
    ctx.addIssue({
      code: z.ZodIssueCode.custom,
      message: `Using a local version for the operator deployment is not supported.`,
    });
    return z.NEVER;
  } else {
    return parsedVersion(version);
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
    allowDowngrade: z.boolean().default(false),
    releaseReference: GitReferenceSchema.optional(),
    sequencer: z
      .object({
        enableBftSequencer: z.boolean().default(false),
      })
      .default({}),
  })
  .strict();

export const SynchronizerMigrationSchema = z
  .object({
    legacy: MigrationInfoSchema.optional(),
    active: MigrationInfoSchema.extend({
      migratingFrom: z.number().optional(),
      version: migrationVersion,
    })
      .strict()
      .default(defaultActiveMigration),
    upgrade: MigrationInfoSchema.optional(),
    archived: z.array(MigrationInfoSchema).optional(),
    activeDatabaseId: z.number().optional(),
  })
  .strict();
