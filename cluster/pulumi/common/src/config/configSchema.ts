// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { z } from 'zod';

import { defaultActiveMigration, SynchronizerMigrationSchema } from './migrationSchema';

// This is a config that's relevant for all (most) pulumi projects. For project-specific configuration,
// define a config schema in the project itself, and parse the Yaml file there. See e.g. cluster/pulumi/infra/src/config.ts
const PulumiProjectConfigSchema = z.object({
  installDataOnly: z.boolean(),
  isExternalCluster: z.boolean(),
  hasPublicInfo: z.boolean(),
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
    // https://cloud.google.com/sql/docs/mysql/backup-recovery/backups#retained-backups
    // controls the number of automated gcp sql backups to retain
    backupsToRetain: z.number().optional(),
  }),
});
export type PulumiProjectConfig = z.infer<typeof PulumiProjectConfigSchema>;
export const ConfigSchema = z.object({
  synchronizerMigration: SynchronizerMigrationSchema.default({
    active: defaultActiveMigration,
  }),
  persistentHeapDumps: z.boolean().default(false),
  pulumiProjectConfig: z
    .object({
      default: PulumiProjectConfigSchema,
    })
    .and(z.record(PulumiProjectConfigSchema.deepPartial())),
});

export type Config = z.infer<typeof ConfigSchema>;

const SingleResourceSchema = z
  .object({
    memory: z.string().optional(),
    cpu: z.string().optional(),
  })
  .optional();

export const K8sResourceSchema = z
  .object({
    limits: SingleResourceSchema,
    requests: SingleResourceSchema,
  })
  .optional();

export const EnvVarConfigSchema = z.object({
  name: z.string(),
  value: z.string(),
});
