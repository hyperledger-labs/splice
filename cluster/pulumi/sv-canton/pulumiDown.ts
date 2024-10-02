import { DomainMigrationIndex } from 'splice-pulumi-common';
import { config } from 'splice-pulumi-common/src/config';

import { downStack } from '../pulumi';
import { runForAllMigrations, stackForMigration, svsToDeploy } from './pulumi';

// used in CI clusters that run HDM to ensure everything is cleaned up
const extraMigrationsToReset =
  config
    .optionalEnv('GLOBAL_DOMAIN_SV_CANTON_EXTRA_MIGRATIONS_RESET')
    ?.split(',')
    .map(id => parseInt(id)) || [];

async function downAllTheStacks() {
  await runForAllMigrations(async stack => {
    await downStack(stack);
  }, false);
  for (const migrationId of extraMigrationsToReset) {
    await Promise.all(
      svsToDeploy.map(async sv => {
        const stack = await stackForMigration(sv, migrationId as DomainMigrationIndex, false);
        await downStack(stack);
      })
    );
  }
}

downAllTheStacks().catch(err => {
  console.error('Failed to run destroy');
  console.error(err);
  process.exit(1);
});
