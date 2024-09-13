import * as automation from '@pulumi/pulumi/automation';
import { DomainMigrationIndex } from 'splice-pulumi-common';
import { config } from 'splice-pulumi-common/src/config';

import { pulumiOpts, runForAllMigrations, stackForMigration, svsToDeploy } from './pulumi';

// used in CI clusters that run HDM to ensure everything is cleaned up
const extraMigrationsToReset =
  config
    .optionalEnv('GLOBAL_DOMAIN_SV_CANTON_EXTRA_MIGRATIONS_RESET')
    ?.split(',')
    .map(id => parseInt(id)) || [];

async function downAllTheStacks() {
  async function downStack(migrationId: DomainMigrationIndex, sv: string, stack: automation.Stack) {
    console.log(`[migration=${migrationId}]Destroying stack for ${sv}`);
    await stack.cancel();
    const refresh = await stack.refresh(pulumiOpts);
    console.log(`Ran refresh: ${JSON.stringify(refresh.summary)}`);
    const destroy = await stack.destroy(pulumiOpts);
    console.log(`[migration=${migrationId}]Destroyed stack for ${sv}`);
    console.log(JSON.stringify(destroy.summary));
  }

  await runForAllMigrations(async (stack, migration, sv) => {
    await downStack(migration.migrationId, sv, stack);
  });
  for (const migrationId of extraMigrationsToReset) {
    await Promise.all(
      svsToDeploy.map(async sv => {
        const stack = await stackForMigration(sv, migrationId as DomainMigrationIndex);
        await downStack(migrationId as DomainMigrationIndex, sv, stack);
      })
    );
  }
}

downAllTheStacks().catch(err => {
  console.error('Failed to run destroy');
  console.error(err);
  process.exit(1);
});
