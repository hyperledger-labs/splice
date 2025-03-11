import {
  config,
  DecentralizedSynchronizerUpgradeConfig,
  DomainMigrationIndex,
} from 'splice-pulumi-common';
import { allSvsToDeploy, svRunbookConfig } from 'splice-pulumi-common-sv';
import { GitFluxRef, gitRepoForRef } from 'splice-pulumi-common/src/operator/flux-source';
import { createStackCR, EnvRefs } from 'splice-pulumi-common/src/operator/stack';

export function installMigrationSpecificStacks(mainReference: GitFluxRef, envRefs: EnvRefs): void {
  const migrations = DecentralizedSynchronizerUpgradeConfig.allExternalMigrations;
  migrations.forEach(migration => {
    const reference = migration.releaseReference
      ? gitRepoForRef(`migration-${migration.id}`, migration.releaseReference)
      : mainReference;
    allSvsToDeploy.forEach(sv => {
      createStackForMigration(sv.nodeName, migration.id, reference, envRefs);
    });
  });
}

function createStackForMigration(
  sv: string,
  migrationId: DomainMigrationIndex,
  reference: GitFluxRef,
  envRefs: EnvRefs
) {
  createStackCR(
    `sv-canton.${sv}-migration-${migrationId}`,
    'sv-canton',
    sv === svRunbookConfig.nodeName && config.envFlag('SUPPORTS_SV_RUNBOOK_RESET'),
    reference,
    envRefs,
    {
      SPLICE_MIGRATION_ID: migrationId.toString(),
      SPLICE_SV: sv,
    }
  );
}
