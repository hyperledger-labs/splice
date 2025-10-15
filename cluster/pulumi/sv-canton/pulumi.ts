// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as automation from '@pulumi/pulumi/automation';

import { allSvNamesToDeploy } from '../common-sv/src/dsoConfig';
import { DeploySvRunbook, isDevNet } from '../common/src/config';
// We have to be explicit with the imports here, if we import a module that creates a pulumi resource running the preview will fail
// as we have no pulumi runtime
import {
  activeVersion,
  allowDowngrade,
  DecentralizedSynchronizerUpgradeConfig,
  DomainMigrationIndex,
  MigrationInfo,
} from '../common/src/domainMigration';
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

const migrations = DecentralizedSynchronizerUpgradeConfig.allMigrations;
export const svsToDeploy = allSvNamesToDeploy;

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
  const migrationsToRunFor: MigrationInfo[] = migrations.concat(
    forceMigrations
      .filter(migration => {
        return !migrationIds.includes(migration);
      })
      .map(id => {
        return {
          id: id,
          version: activeVersion,
          allowDowngrade: allowDowngrade,
          // This doesn't actually matter, this is only used for down/refresh.
          sequencer: { enableBftSequencer: false },
        };
      })
  );
  return migrationsToRunFor.flatMap(migration => {
    return svsToRunFor.map(sv => {
      console.error(`Adding operation for migration ${migration.id} and sv ${sv}`);
      return {
        name: `${operation}-canton-M${migration.id}-${sv}`,
        // eslint-disable-next-line promise/prefer-await-to-then
        promise: stackForMigration(sv, migration.id, requiresExistingStack).then(stack => {
          return runForStack(stack, migration, sv);
        }),
      };
    });
  });
}
