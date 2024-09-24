import {
  DecentralizedSynchronizerUpgradeConfig,
  DomainMigrationIndex,
  externalMigrations,
} from 'splice-pulumi-common';
import { allSvsToDeploy, svRunbookConfig } from 'splice-pulumi-common-sv';

import { GitFluxRef, gitRepoForRef } from '../flux';
import { createStackCR } from './stack';

export function installMigrationSpecificStacks(mainReference: GitFluxRef): void {
  const migrations = externalMigrations(DecentralizedSynchronizerUpgradeConfig);
  migrations.externalMigrations.forEach(migration => {
    const reference = migration.releaseReference
      ? gitRepoForRef(`migration-${migration.id}`, migration.releaseReference)
      : mainReference;
    allSvsToDeploy.forEach(sv => {
      createStackForMigration(sv.nodeName, migration.id, reference);
    });
  });
}

function createStackForMigration(
  sv: string,
  migrationId: DomainMigrationIndex,
  reference: GitFluxRef
) {
  createStackCR(
    `${sv}-migration-${migrationId}`,
    'sv-canton',
    sv === svRunbookConfig.nodeName,
    reference,
    {
      SPLICE_MIGRATION_ID: migrationId.toString(),
      SPLICE_SV: sv,
    }
  );
}
