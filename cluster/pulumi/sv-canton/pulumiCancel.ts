import { DomainMigrationIndex } from 'splice-pulumi-common';
import { config } from 'splice-pulumi-common/src/config';

import { awaitAllOrThrowAllExceptions } from '../pulumi';
import { runForAllMigrations, stackForMigration, svsToDeploy } from './pulumi';

// used in CI clusters that run HDM to ensure everything is cleaned up
const extraMigrationsToReset =
  config
    .optionalEnv('GLOBAL_DOMAIN_SV_CANTON_EXTRA_MIGRATIONS_RESET')
    ?.split(',')
    .map(id => parseInt(id)) || [];

async function cancelMigrationId(migrationId: DomainMigrationIndex): Promise<void> {
  const data = await Promise.allSettled(
    svsToDeploy.map(async sv => {
      const stack = await stackForMigration(sv, migrationId, false);
      await stack.cancel();
    })
  );
  const rejected = (
    data.find(res => res.status === 'rejected') as PromiseRejectedResult | undefined
  )?.reason;
  if (rejected) {
    throw new Error(rejected);
  }
}

async function cancelAllTheStacks() {
  await runForAllMigrations(async stack => {
    await stack.cancel();
  }, false).then(async () => {
    const cancelOperations: Promise<void>[] = [];
    for (const migrationId of extraMigrationsToReset) {
      cancelOperations.push(cancelMigrationId(migrationId));
    }
    await awaitAllOrThrowAllExceptions(cancelOperations);
    return null;
  });
}

cancelAllTheStacks().catch(err => {
  console.error('Failed to run cancel');
  console.error(err);
  process.exit(1);
});
