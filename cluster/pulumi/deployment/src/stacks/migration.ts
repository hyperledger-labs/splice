// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import {
  CLUSTER_BASENAME,
  config,
  DecentralizedSynchronizerUpgradeConfig,
  DomainMigrationIndex,
} from '@lfdecentralizedtrust/splice-pulumi-common';
import {
  allSvNamesToDeploy,
  svRunbookNodeName,
} from '@lfdecentralizedtrust/splice-pulumi-common-sv/src/dsoConfig';
import { deploymentConf } from '@lfdecentralizedtrust/splice-pulumi-common/src/operator/config';
import {
  GitFluxRef,
  gitRepoForRef,
  StackFromRef,
} from '@lfdecentralizedtrust/splice-pulumi-common/src/operator/flux-source';
import {
  createStackCR,
  EnvRefs,
} from '@lfdecentralizedtrust/splice-pulumi-common/src/operator/stack';

export function getMigrationSpecificStacksFromMainReference(): StackFromRef[] {
  if (deploymentConf.projectsToDeploy.has('sv-canton')) {
    const migrations = DecentralizedSynchronizerUpgradeConfig.allMigrations;
    return migrations
      .filter(migration => !migration.releaseReference)
      .map(migration =>
        allSvNamesToDeploy.map(nodeName => {
          return {
            project: 'sv-canton',
            stack: `sv-canton.${nodeName}-migration-${migration.id}.${CLUSTER_BASENAME}`,
          };
        })
      )
      .flat();
  } else {
    return [];
  }
}

export function installMigrationSpecificStacks(
  mainReference: GitFluxRef,
  envRefs: EnvRefs,
  namespace: string,
  gcpSecret: k8s.core.v1.Secret
): void {
  if (deploymentConf.projectsToDeploy.has('sv-canton')) {
    const migrations = DecentralizedSynchronizerUpgradeConfig.allMigrations;
    migrations.forEach(migration => {
      const reference = migration.releaseReference
        ? gitRepoForRef(
            `migration-${migration.id}`,
            migration.releaseReference,
            allSvNamesToDeploy.map(nodeName => {
              return {
                project: 'sv-canton',
                stack: `sv-canton.${nodeName}-migration-${migration.id}.${CLUSTER_BASENAME}`,
              };
            })
          )
        : mainReference;
      allSvNamesToDeploy.forEach(nodeName => {
        createStackForMigration(nodeName, migration.id, reference, envRefs, namespace, gcpSecret);
      });
    });
  }
}

function createStackForMigration(
  sv: string,
  migrationId: DomainMigrationIndex,
  reference: GitFluxRef,
  envRefs: EnvRefs,
  namespace: string,
  gcpSecret: k8s.core.v1.Secret
) {
  createStackCR(
    `sv-canton.${sv}-migration-${migrationId}`,
    'sv-canton',
    namespace,
    sv === svRunbookNodeName && config.envFlag('SUPPORTS_SV_RUNBOOK_RESET'),
    reference,
    envRefs,
    gcpSecret,
    {
      SPLICE_MIGRATION_ID: migrationId.toString(),
      SPLICE_SV: sv,
    }
  );
}
