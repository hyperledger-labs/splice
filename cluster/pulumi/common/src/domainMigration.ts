import { CnChartVersion, parsedVersion } from './artifacts';
import { config } from './config';
import { defaultVersion } from './helm';

export class DecentralizedSynchronizerMigrationConfig {
  // the current running migration, to which the ingresses point, and it's expected to be the active CN network
  // this is the only migration that contains the CN apps
  active: MigrationInfo;
  // if set then the canton components associated with this migration id are kept running, does not impact the CN apps
  legacy?: MigrationInfo;
  // the next migration id that we are preparing
  // this is used to prepare the canton components for the upgrade
  upgrade?: MigrationInfo;
  // indicates that during this run we are actually migrating from this id to the active migration ID
  // used to configure  the CN apps for the migration
  migratingFromActiveId?: DomainMigrationIndex;
  activeDatabaseId?: DomainMigrationIndex;

  constructor(
    active: MigrationInfo,
    legacy?: MigrationInfo,
    upgrade?: MigrationInfo,
    migratingFromActiveId?: DomainMigrationIndex,
    activeDatabaseId?: DomainMigrationIndex
  ) {
    this.active = active;
    this.legacy = legacy;
    this.upgrade = upgrade;
    this.migratingFromActiveId = migratingFromActiveId;
    this.activeDatabaseId = activeDatabaseId;
  }

  // TODO(#10074) read this from current deployment if available
  static fromEnv(): DecentralizedSynchronizerMigrationConfig {
    return new DecentralizedSynchronizerMigrationConfig(
      new MigrationInfo(
        processMigrationId(config.optionalEnv('GLOBAL_DOMAIN_ACTIVE_MIGRATION_ID')) ||
          DefaultMigrationId,
        defaultVersion,
        processMigrationProvider(config.optionalEnv('GLOBAL_DOMAIN_ACTIVE_MIGRATION_PROVIDER'))
      ),
      MigrationInfo.fromEnv(
        config.optionalEnv('GLOBAL_DOMAIN_LEGACY_MIGRATION_ID'),
        config.optionalEnv('GLOBAL_DOMAIN_LEGACY_VERSION'),
        config.optionalEnv('GLOBAL_DOMAIN_LEGACY_MIGRATION_PROVIDER')
      ),
      MigrationInfo.fromEnv(
        config.optionalEnv('GLOBAL_DOMAIN_UPGRADE_MIGRATION_ID'),
        config.optionalEnv('GLOBAL_DOMAIN_UPGRADE_VERSION'),
        config.optionalEnv('GLOBAL_DOMAIN_UPGRADE_MIGRATION_PROVIDER')
      ),
      processMigrationId(config.optionalEnv('GLOBAL_DOMAIN_MIGRATE_FROM_MIGRATION_ID')),
      processMigrationId(config.optionalEnv('GLOBAL_DOMAIN_DATABASE_ACTIVE_ID'))
    );
  }

  allMigrationInfos(): MigrationInfo[] {
    return [this.active]
      .concat(this.legacy ? [this.legacy] : [])
      .concat(this.upgrade ? [this.upgrade] : []);
  }

  isStillRunning(id: DomainMigrationIndex): boolean {
    return this.allMigrationInfos().some(info => info.migrationId == id);
  }

  isRunningMigration(): boolean {
    return (
      this.migratingFromActiveId != undefined &&
      this.migratingFromActiveId != this.active.migrationId
    );
  }

  migratingNodeConfig(): {
    migration: { id: DomainMigrationIndex; migrating: boolean; legacyId?: DomainMigrationIndex };
  } {
    return {
      migration: {
        id: this.active.migrationId,
        migrating: this.isRunningMigration(),
        legacyId: this.legacy?.migrationId,
      },
    };
  }
}

export enum MigrationProvider {
  INTERNAL,
  EXTERNAL,
}

class MigrationInfo {
  migrationId: DomainMigrationIndex;
  version: CnChartVersion;
  provider: MigrationProvider;

  constructor(
    migrationId: DomainMigrationIndex,
    version: CnChartVersion,
    migrationProvider: MigrationProvider
  ) {
    this.migrationId = migrationId;
    this.version = version;
    this.provider = migrationProvider;
  }

  static fromEnv(
    maybeIdValue?: string,
    maybeVersionValue?: string,
    maybeMigrationProviderValue?: string
  ): MigrationInfo | undefined {
    const migrationId = processMigrationId(maybeIdValue);

    if (migrationId == undefined) {
      return undefined;
    } else {
      return new MigrationInfo(
        migrationId,
        processVersion(maybeVersionValue),
        processMigrationProvider(maybeMigrationProviderValue)
      );
    }
  }
}

function processMigrationProvider(maybeMigrationProviderValue?: string): MigrationProvider {
  switch (maybeMigrationProviderValue) {
    case 'internal':
      return MigrationProvider.INTERNAL;
    case 'external':
      return MigrationProvider.EXTERNAL;
    case undefined:
      return MigrationProvider.INTERNAL;
    default:
      throw new Error(`Cannot process ${maybeMigrationProviderValue} as migration provider`);
  }
}

function processMigrationId(maybeValue?: string): DomainMigrationIndex | undefined {
  if (maybeValue == undefined) {
    return undefined;
  }
  const index = Number(maybeValue);
  if (index >= 0 && index < 10) {
    return index as DomainMigrationIndex;
  } else {
    throw new Error(`Cannot process ${maybeValue} as domain index`);
  }
}

function processVersion(maybeValue?: string): CnChartVersion {
  if (maybeValue == undefined) {
    return defaultVersion;
  } else {
    return parsedVersion(maybeValue);
  }
}

export const DefaultMigrationId = 0;

export type DomainMigrationIndex = 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9;

export function installMigrationIdSpecificComponent<T>(
  decentralizedSynchronizerUpgradeConfig: DecentralizedSynchronizerMigrationConfig,
  component: (migrationId: DomainMigrationIndex, isActive: boolean, version: CnChartVersion) => T
): {
  activeComponent: T;
  legacyComponent?: T;
  upgradeComponent?: T;
} {
  return {
    activeComponent: component(
      decentralizedSynchronizerUpgradeConfig.active.migrationId,
      true,
      decentralizedSynchronizerUpgradeConfig.active.version
    ),
    legacyComponent:
      decentralizedSynchronizerUpgradeConfig.legacy != undefined
        ? component(
            decentralizedSynchronizerUpgradeConfig.legacy.migrationId,
            false,
            decentralizedSynchronizerUpgradeConfig.legacy.version
          )
        : undefined,
    upgradeComponent:
      decentralizedSynchronizerUpgradeConfig.upgrade != undefined
        ? component(
            decentralizedSynchronizerUpgradeConfig.upgrade.migrationId,
            false,
            decentralizedSynchronizerUpgradeConfig.upgrade.version
          )
        : undefined,
  };
}
