import * as automation from '@pulumi/pulumi/automation';
import { MigrationProvider } from 'splice-pulumi-common';
import { dsoSize } from 'splice-pulumi-common-sv/src/dsoConfig';
import { DeploySvRunbook, isDevNet } from 'splice-pulumi-common/src/config';
// We have to be explicit with the imports here, if we import a module that creates a pulumi resource running the preview will fail
// as we have no pulumi runtime
import {
  activeVersion,
  DecentralizedSynchronizerUpgradeConfig,
  DomainMigrationIndex,
  MigrationInfo,
} from 'splice-pulumi-common/src/domainMigration';

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

const migrations = DecentralizedSynchronizerUpgradeConfig.allExternalMigrations;
const coreSvs = Array.from({ length: dsoSize }, (_, index) => `sv-${index + 1}`);
export const svsToDeploy = coreSvs.concat(DeploySvRunbook ? ['sv'] : []);

export function runSvCantonForAllMigrations<T>(
  operation: string,
  runForStack: (stack: automation.Stack, migration: MigrationInfo, sv: string) => Promise<T>,
  requiresExistingStack: boolean,
  // allow the ability to force run for the runbook in certain cases
  // this also requires that the cluster is a dev cluster
  // used to ensure down/refresh always takes care of the runbook as well
  forceSvRunbook: boolean = false,
  forceMigrations: DomainMigrationIndex[] = []
): { name: string; promise: Promise<T> }[] {
  const svsToRunFor = svsToDeploy.concat(
    !DeploySvRunbook && forceSvRunbook && isDevNet ? ['sv'] : []
  );
  return runSvCantonForSvs(
    svsToRunFor,
    operation,
    runForStack,
    requiresExistingStack,
    forceMigrations
  );
}

export function runSvCantonForSvs<T>(
  svsToRunFor: string[],
  operation: string,
  runForStack: (stack: automation.Stack, migration: MigrationInfo, sv: string) => Promise<T>,
  requiresExistingStack: boolean,
  forceMigrations: DomainMigrationIndex[] = []
): { name: string; promise: Promise<T> }[] {
  const migrationIds = migrations.map(migration => migration.id);
  console.log(
    `Running for migration ${JSON.stringify(migrationIds)} and svs ${JSON.stringify(svsToRunFor)}`
  );
  const migrationsToRunFor = migrations.concat(
    forceMigrations
      .filter(migration => {
        return !migrationIds.includes(migration);
      })
      .map(id => {
        return {
          id: id,
          version: activeVersion,
          provider: MigrationProvider.EXTERNAL,
        };
      })
  );
  return migrationsToRunFor.flatMap(migration => {
    return svsToRunFor.map(sv => {
      console.error(`Adding operation for migration ${migration.id} and sv ${sv}`);
      return {
        name: `${operation}-canton-M${migration.id}-${sv}`,
        promise: stackForMigration(sv, migration.id, requiresExistingStack).then(stack => {
          return runForStack(stack, migration, sv);
        }),
      };
    });
  });
}
