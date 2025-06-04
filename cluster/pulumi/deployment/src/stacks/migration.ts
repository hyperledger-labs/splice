// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  CLUSTER_BASENAME,
  config,
  DecentralizedSynchronizerUpgradeConfig,
  DomainMigrationIndex,
} from 'splice-pulumi-common';
import { allSvsToDeploy, svRunbookConfig } from 'splice-pulumi-common-sv';
import {
  GitFluxRef,
  gitRepoForRef,
  StackFromRef,
} from 'splice-pulumi-common/src/operator/flux-source';
import { createStackCR, EnvRefs } from 'splice-pulumi-common/src/operator/stack';

export function getMigrationSpecificStacksFromMainReference(): StackFromRef[] {
  const migrations = DecentralizedSynchronizerUpgradeConfig.allMigrations;
  return migrations
    .filter(migration => !migration.releaseReference)
    .map(migration =>
      allSvsToDeploy.map(sv => {
        return {
          project: 'sv-canton',
          stack: `${sv.nodeName}-migration-${migration.id}.${CLUSTER_BASENAME}`,
        };
      })
    )
    .flat();
}

export function installMigrationSpecificStacks(
  mainReference: GitFluxRef,
  envRefs: EnvRefs,
  namespace: string
): void {
  const migrations = DecentralizedSynchronizerUpgradeConfig.allMigrations;
  migrations.forEach(migration => {
    const reference = migration.releaseReference
      ? gitRepoForRef(
          `migration-${migration.id}`,
          migration.releaseReference,
          allSvsToDeploy.map(sv => {
            return {
              project: 'sv-canton',
              stack: `${sv.nodeName}-migration-${migration.id}.${CLUSTER_BASENAME}`,
            };
          })
        )
      : mainReference;
    allSvsToDeploy.forEach(sv => {
      createStackForMigration(sv.nodeName, migration.id, reference, envRefs, namespace);
    });
  });
}

function createStackForMigration(
  sv: string,
  migrationId: DomainMigrationIndex,
  reference: GitFluxRef,
  envRefs: EnvRefs,
  namespace: string
) {
  createStackCR(
    `sv-canton.${sv}-migration-${migrationId}`,
    'sv-canton',
    namespace,
    sv === svRunbookConfig.nodeName && config.envFlag('SUPPORTS_SV_RUNBOOK_RESET'),
    reference,
    envRefs,
    undefined,
    {
      SPLICE_MIGRATION_ID: migrationId.toString(),
      SPLICE_SV: sv,
    }
  );
}
