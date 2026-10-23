// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Stack } from '@pulumi/pulumi/automation';
import { awaitAllOrThrowAllExceptions, ensureStackSettingsAreUpToDate } from '../pulumi';
import { runSvCantonForAllMigrations, runSvCantonForDsyncForAllMigrations } from './pulumi';
import type { MigrationInfo } from '@canton-network/splice-pulumi-common';

async function preview(stack: Stack, migration: MigrationInfo, nodeName: string) {
      await ensureStackSettingsAreUpToDate(stack);
      const preview = await stack.preview({
        parallel: 128,
        diff: true,
        color: 'always',
      });
  console.log(`[migration=${migration.id}]Previewing stack for ${nodeName}`);
      console.error(preview.stderr);
      console.log(preview.stdout);
      console.log(JSON.stringify(preview.changeSummary));
}

awaitAllOrThrowAllExceptions(
  runSvCantonForAllMigrations(
    'preview',
    (stack, migration, sv) => preview(stack, migration, sv),
    true,
    true
  ).concat(runSvCantonForDsyncForAllMigrations(
    'preview',
    (stack, migration, dsyncNode) => preview(stack, migration, dsyncNode),
    true
  ))
).catch(err => {
  console.error('Failed to run preview');
  console.error(err);
  process.exit(1);
});
