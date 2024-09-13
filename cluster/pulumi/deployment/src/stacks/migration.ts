import {
  DecentralizedSynchronizerMigrationConfig,
  DomainMigrationIndex,
  externalMigrations,
} from 'splice-pulumi-common';
import { allSvsToDeploy, svRunbookConfig } from 'splice-pulumi-common-sv';

import { createStackCR } from './stack';

export function installMigrationSpecificStacks(): void {
  const migrations = externalMigrations(DecentralizedSynchronizerMigrationConfig.fromEnv());
  migrations.externalMigrations.forEach(migration => {
    allSvsToDeploy.forEach(sv => {
      createStackForMigration(sv.nodeName, migration.migrationId);
    });
  });
}

function createStackForMigration(sv: string, migrationId: DomainMigrationIndex) {
  createStackCR(`${sv}-migration-${migrationId}`, sv === svRunbookConfig.nodeName, {
    SPLICE_MIGRATION_ID: migrationId.toString(),
    SPLICE_SV: sv,
  });
}
