import { config } from 'splice-pulumi-common/src/config';
import { DomainMigrationIndex } from 'splice-pulumi-common/src/domainMigration';

import {
  awaitAllOrThrowAllExceptions,
  downStack,
  operation,
  Operation,
  PulumiAbortController,
} from '../pulumi';
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

const abortController = new PulumiAbortController();

async function downMigrationId(migrationId: DomainMigrationIndex): Promise<void> {
  const data = await Promise.allSettled(
    svsToDeploy.map(async sv => {
      const stack = await stackForMigration(sv, migrationId, false);
      await downStack(stack, abortController);
    })
  );
  const rejected = (
    data.find(res => res.status === 'rejected') as PromiseRejectedResult | undefined
  )?.reason;
  if (rejected) {
    throw new Error(rejected);
  }
}

// eslint-disable-next-line @typescript-eslint/explicit-module-boundary-types
export async function downAllTheStacks() {
  await runForAllMigrations(async stack => {
    await downStack(stack, abortController);
  }, false).then(async () => {
    const downOperations: Operation[] = [];
    for (const migrationId of extraMigrationsToReset) {
      downOperations.push(operation(`down-sv-canton-${migrationId}`, downMigrationId(migrationId)));
    }
    await awaitAllOrThrowAllExceptions(downOperations);
    return null;
  });
}

export async function cancelAllTheStacks() {
  await runForAllMigrations(async stack => {
    await stack.cancel();
  }, false).then(async () => {
    const cancelOperations: Operation[] = [];
    for (const migrationId of extraMigrationsToReset) {
      cancelOperations.push(
        operation(`cancel-sv-canton-${migrationId}`, cancelMigrationId(migrationId))
      );
    }
    await awaitAllOrThrowAllExceptions(cancelOperations);
    return null;
  });
}
