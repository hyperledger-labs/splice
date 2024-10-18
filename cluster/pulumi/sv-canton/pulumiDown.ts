import { DomainMigrationIndex } from 'splice-pulumi-common';
import { config } from 'splice-pulumi-common/src/config';

import { awaitAllOrThrowAllExceptions, downStack, PulumiAbortController } from '../pulumi';
import { runForAllMigrations, stackForMigration, svsToDeploy } from './pulumi';

const abortController = new PulumiAbortController();

// used in CI clusters that run HDM to ensure everything is cleaned up
const extraMigrationsToReset =
  config
    .optionalEnv('GLOBAL_DOMAIN_SV_CANTON_EXTRA_MIGRATIONS_RESET')
    ?.split(',')
    .map(id => parseInt(id)) || [];

async function downMigrationId(migrationId: DomainMigrationIndex): Promise<void> {
  const data = await Promise.allSettled(
    svsToDeploy.map(async sv => {
      const stack = await stackForMigration(sv, migrationId, false);
      await downStack(stack, abortController);
    })
  );
  const rejected = (data.find((res) => res.status === "rejected") as PromiseRejectedResult | undefined)?.reason
  if (rejected) {
    throw new Error(rejected);
  }
}

async function downAllTheStacks() {
  await runForAllMigrations(async stack => {
    await downStack(stack, abortController);
  }, false).then(async () => {
    const downOperations: Promise<void>[] = [];
    for (const migrationId of extraMigrationsToReset) {
      downOperations.push(downMigrationId(migrationId));
    }
    awaitAllOrThrowAllExceptions(downOperations);
    return null;
  });
}

downAllTheStacks().catch(err => {
  console.error('Failed to run destroy');
  console.error(err);
  process.exit(1);
});
