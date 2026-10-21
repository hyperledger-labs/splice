// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as fs from 'fs';
import { SPLICE_ROOT } from '@canton-network/splice-pulumi-common/src/utils';
import { z } from 'zod';

// The migrations bigquery-cloudsql.sh may wait for before it (re)applies the
// Datastream publications. V078 renames the replicated update_history tables,
// so the publications have to be applied again after it.
const flywayMigrationsToWaitFor = [
  'V068__app_activity_record_meta.sql',
  'V078__partition_update_history.sql',
] as const;

export function checkFlywayMigrationsExist(dir: string, migrations: readonly string[]): void {
  const missing = migrations.filter(migration => !fs.existsSync(`${dir}/${migration}`));
  if (missing.length > 0) {
    throw new Error(`Flyway migrations allowed in scanApp.bigQuery are not in ${dir}: ${missing}`);
  }
}

checkFlywayMigrationsExist(
  `${SPLICE_ROOT}/apps/common/src/main/resources/db/migration/canton-network/postgres/stable`,
  flywayMigrationsToWaitFor
);

export const FlywayMigrationToWaitForSchema = z
  .enum(flywayMigrationsToWaitFor)
  .default('V068__app_activity_record_meta.sql');
