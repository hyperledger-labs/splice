// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { merge } from 'lodash';
import { z } from 'zod';

import { spliceConfig } from './config';

export const CloudSqlConfigSchema = z.object({
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
  flags: z.record(z.string(), z.string()).default({}),
  enableAutoBackups: z.boolean().default(true),
  databaseVersion: z.string().default('POSTGRES_18'),
});
export type CloudSqlConfig = z.infer<typeof CloudSqlConfigSchema>;

// Deployment strategy:
// - If no migration is necessary, just default docker-image will deploy the latest version
// - If you want to migrate data, you need to, in this order:
//   1) deployment = 'legacy-helm-chart' (this is the original state, which uses pg14, unconfigurable)
//   2) When the time to migrate comes, scale down all pods that use the database and set deployment = 'migrate'
//   3) Once the migration is complete (i.e., the DB pod is up and running and apps can connect to it), set deployment = 'docker-image'
// Once everything has been migrated we can drop this, as everything will be using docker-image.
export const SplicePostgresMigrateSchema = z.object({
  deployment: z.literal('migrate'),
  migrationVolumeSize: z.string(),
  postgresImage: z.string(),
});
export type SplicePostgresMigrateConfig = z.infer<typeof SplicePostgresMigrateSchema>;
export const SplicePostgresDockerImageSchema = z.object({
  deployment: z.literal('docker-image'),
  postgresImage: z.string(),
});
export type SplicePostgresDockerImageConfig = z.infer<typeof SplicePostgresDockerImageSchema>;
export const SplicePostgresSchema = z.union([
  z.object({ deployment: z.literal('legacy-helm-chart') }),
  SplicePostgresMigrateSchema,
  SplicePostgresDockerImageSchema,
]);

export type SplicePostgresConfig = z.infer<typeof SplicePostgresSchema>;
