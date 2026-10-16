// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { automation } from '@pulumi/pulumi';
import { awaitAllOrThrowAllExceptions, PulumiAbortController } from '../pulumi';
import { pulumiOptsForMigration, runSvCantonForAllMigrations, runSvCantonForDsyncForAllMigrations } from './pulumi';
import { MigrationInfo } from '@canton-network/splice-pulumi-common';

const abortController = new PulumiAbortController();

async function refreshUp(
  stack: automation.Stack,
  migration: MigrationInfo,
  nodeType: 'sv' | 'dsync',
  nodeName: string
): Promise<void> {
  console.log(`[migration=${migration.id}]Updating stack for ${nodeType} node ${nodeName}`);
  const pulumiOpts = pulumiOptsForMigration(migration.id, nodeName, abortController.signal);
      await stack.refresh(pulumiOpts).catch(err => {
        abortController.abort();
        throw err;
      });
      const result = await stack.up(pulumiOpts).catch(err => {
        abortController.abort();
        throw err;
      });
  console.log(`[migration=${migration.id}]Updated stack for ${nodeType} node ${nodeName}`);
      console.log(JSON.stringify(result.summary));
}

awaitAllOrThrowAllExceptions(
  runSvCantonForAllMigrations(
    'up',
    async (stack, migration, sv) => await refreshUp(stack, migration, 'sv', sv),
    false
  ).concat(
    runSvCantonForDsyncForAllMigrations(
      'up',
      (stack, migration, dsyncNode) => refreshUp(stack, migration, 'dsync', dsyncNode),
      false
    )
  )
).catch(err => {
  console.error('Failed to run up');
  console.error(err);
  process.exit(1);
});
