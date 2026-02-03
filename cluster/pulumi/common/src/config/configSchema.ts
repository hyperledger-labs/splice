// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { z } from 'zod';

import { CloudSqlConfigSchema } from './cloudSql';
import { defaultActiveMigration, SynchronizerMigrationSchema } from './migrationSchema';

// This is a config that's relevant for all (most) pulumi projects. For project-specific configuration,
// define a config schema in the project itself, and parse the Yaml file there. See e.g. cluster/pulumi/infra/src/config.ts
const PulumiProjectConfigSchema = z.object({
  installDataOnly: z.boolean(),
  isExternalCluster: z.boolean(),
  hasPublicInfo: z.boolean(),
  interAppsDependencies: z.boolean(),
  cloudSql: CloudSqlConfigSchema,
  allowDowngrade: z.boolean(),
  replacePostgresStatefulSetOnChanges: z.boolean().default(false),
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

export type K8sResourceSchema = z.infer<typeof K8sResourceSchema>;

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
