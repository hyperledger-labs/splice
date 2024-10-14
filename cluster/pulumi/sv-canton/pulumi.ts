import * as automation from '@pulumi/pulumi/automation';
import { config } from 'splice-pulumi-common/src/config';
// We have to be explicit with the imports here, if we import a module that creates a pulumi resource running the preview will fail
// as we have no pulumi runtime
import {
  DecentralizedSynchronizerUpgradeConfig,
  DomainMigrationIndex,
  MigrationInfo,
} from 'splice-pulumi-common/src/domainMigration';
import { DeploySvRunbook } from 'splice-pulumi-common/src/utils';

import { pulumiOptsWithPrefix, stack } from '../pulumi';

export function pulumiOptsForMigration(
  migration: DomainMigrationIndex,
  sv: string,
  abortSignal: AbortSignal
): {
  parallel: number;
  onOutput: (output: string) => void;
  signal: AbortSignal;
} {
  return pulumiOptsWithPrefix(`[migration=${migration},sv=${sv}]`, abortSignal);
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
const migrations = DecentralizedSynchronizerUpgradeConfig.allExternalMigrations;
const coreSvs = onlyRunbook ? [] : Array.from({ length: dsoSize }, (_, index) => `sv-${index + 1}`);
export const svsToDeploy = coreSvs.concat(DeploySvRunbook ? ['sv'] : []);

export async function runForAllMigrations(
  runForStack: (stack: automation.Stack, migration: MigrationInfo, sv: string) => Promise<void>,
  requiresExistingStack: boolean
): Promise<void> {
  console.log(
    `Running for migration ${JSON.stringify(migrations)} and svs ${JSON.stringify(svsToDeploy)}`
  );
  for (const migration of migrations) {
    console.log(`Running for migration ${migration.id}`);

    const data = await Promise.allSettled(
      svsToDeploy.map(async sv => {
        const stack = await stackForMigration(sv, migration.id, requiresExistingStack);
        await runForStack(stack, migration, sv);
      })
    );
    const rejected = (data.find((res) => res.status === "rejected") as PromiseRejectedResult | undefined)?.reason
    if (!rejected) {
      throw new Error(rejected);
    }
  }
}
