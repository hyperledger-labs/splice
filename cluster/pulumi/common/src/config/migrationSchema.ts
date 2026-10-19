// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { z } from 'zod';

import { CnChartVersionSchema } from './versionSchema';

export const defaultActiveMigration = {
  id: 0,
  version: 'local',
  sequencer: {
    enableBftSequencer: false,
  },
};

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
    id: z.number().gte(0),
    version: CnChartVersionSchema,
    releaseReference: GitReferenceSchema.optional(),
    sequencer: z
      .object({
        enableBftSequencer: z.boolean().default(true),
        // Use a separate DB server for DABFT.
        dedicatedBftSequencerDb: z.boolean().default(true),
      })
      .strict()
      .prefault({}),
    cometbft: z
      .object({
        volumeSize: z.string().optional(),
      })
      .optional(),
  })
  .strict();

export const SynchronizerMigrationSchema = z
  .object({
    legacy: MigrationInfoSchema.optional(),
    additionalLegacy: z.array(MigrationInfoSchema).optional(),
    active: MigrationInfoSchema.extend({
      migratingFrom: z.number().optional(),
      version: CnChartVersionSchema,
    })
      .strict()
      .prefault(defaultActiveMigration),
    upgrade: MigrationInfoSchema.optional(),
    archived: z.array(MigrationInfoSchema).optional(),
    activeDatabaseId: z.number().optional(),
    attachPvc: z.boolean().default(true),
    frozenMigrationId: z.number(),
  })
  .strict();
