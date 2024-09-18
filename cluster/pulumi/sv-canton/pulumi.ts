import * as automation from '@pulumi/pulumi/automation';
import { config } from 'splice-pulumi-common/src/config';
// We have to be explicit with the imports here, if we import a module that creates a pulumi resource running the preview will fail
// as we have no pulumi runtime
import {
  DecentralizedSynchronizerMigrationConfig,
  DomainMigrationIndex,
  externalMigrations,
  MigrationInfo,
} from 'splice-pulumi-common/src/domainMigration';
import { DeploySvRunbook } from 'splice-pulumi-common/src/utils';

import { pulumiOptsWithPrefix, stack } from '../pulumi';

export function pulumiOptsForMigration(
  migration: DomainMigrationIndex,
  sv: string
): {
  parallel: number;
  onOutput: (output: string) => void;
} {
  return pulumiOptsWithPrefix(`[migration=${migration},sv=${sv}]`);
}

export async function stackForMigration(
  nodeName: string,
  migrationId: DomainMigrationIndex,
  requiresExistingStack: boolean
): Promise<automation.Stack> {
  return stack(
    'sv-canton',
    `sv-canton.${nodeName}-migration-${migrationId}`,
    requiresExistingStack,
    {
      SPLICE_MIGRATION_ID: migrationId.toString(),
      SPLICE_SV: nodeName,
    }
  );
}

const onlyRunbook = config.envFlag('SPLICE_DEPLOY_ONLY_SV_RUNBOOK');
const dsoSize = parseInt(config.requireEnv('DSO_SIZE'));
const migrations = externalMigrations(DecentralizedSynchronizerMigrationConfig.fromEnv());
const coreSvs = onlyRunbook ? [] : Array.from({ length: dsoSize }, (_, index) => `sv-${index + 1}`);
export const svsToDeploy = coreSvs.concat(DeploySvRunbook ? ['sv'] : []);

export async function runForAllMigrations(
  runForStack: (stack: automation.Stack, migration: MigrationInfo, sv: string) => Promise<void>,
  requiresExistingStack: boolean
): Promise<void> {
  console.log(
    `Running for migration ${JSON.stringify(migrations)} and svs ${JSON.stringify(svsToDeploy)}`
  );
  for (const migration of migrations.externalMigrations) {
    console.log(`Running for migration ${migration.migrationId}`);

    await Promise.all(
      svsToDeploy.map(async sv => {
        const stack = await stackForMigration(sv, migration.migrationId, requiresExistingStack);
        await runForStack(stack, migration, sv);
      })
    );
  }
}
