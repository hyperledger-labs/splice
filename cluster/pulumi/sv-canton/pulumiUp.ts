// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { awaitAllOrThrowAllExceptions, PulumiAbortController } from '../pulumi';
import { pulumiOptsForMigration, runSvCantonForAllMigrations, runSvCantonForDsyncForAllMigrations } from './pulumi';

const abortController = new PulumiAbortController();

const dSyncOps = runSvCantonForDsyncForAllMigrations(
    'up',
  async (stack, migration, dsyncNode) => {
    console.log(`[migration=${migration.id}]Updating stack for dsync node ${dsyncNode}`);
    const pulumiOpts = pulumiOptsForMigration(migration.id, dsyncNode, abortController.signal);
      await stack.refresh(pulumiOpts).catch(err => {
        abortController.abort();
        throw err;
      });
      const result = await stack.up(pulumiOpts).catch(err => {
        abortController.abort();
        throw err;
      });
    console.log(`[migration=${migration.id}]Updated stack for dsync node ${dsyncNode}`);
      console.log(JSON.stringify(result.summary));
    },
    false
);

awaitAllOrThrowAllExceptions(
  // runSvCantonForAllMigrations(
  //   'up',
  //   async (stack, migration, sv) => {
  //     console.log(`[migration=${migration.id}]Updating stack for ${sv}`);
  //     const pulumiOpts = pulumiOptsForMigration(migration.id, sv, abortController.signal);
  //     await stack.refresh(pulumiOpts).catch(err => {
  //       abortController.abort();
  //       throw err;
  //     });
  //     const result = await stack.up(pulumiOpts).catch(err => {
  //       abortController.abort();
  //       throw err;
  //     });
  //     console.log(`[migration=${migration.id}]Updated stack for ${sv}`);
  //     console.log(JSON.stringify(result.summary));
  //   },
  //   false
  // ).concat(
  dSyncOps
// )
).catch(err => {
  console.error('Failed to run up');
  console.error(err);
  process.exit(1);
});
