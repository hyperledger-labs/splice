import { CnChartVersion, defaultVersion } from './helm';

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

  constructor(
    active: MigrationInfo,
    legacy?: MigrationInfo,
    upgrade?: MigrationInfo,
    migratingFromActiveId?: DomainMigrationIndex
  ) {
    this.active = active;
    this.legacy = legacy;
    this.upgrade = upgrade;
    this.migratingFromActiveId = migratingFromActiveId;
  }

  // TODO(#10074) read this from current deployment if available
  static fromEnv(): DecentralizedSynchronizerMigrationConfig {
    return new DecentralizedSynchronizerMigrationConfig(
      new MigrationInfo(
        processMigrationId(process.env.GLOBAL_DOMAIN_ACTIVE_MIGRATION_ID) || DefaultMigrationId,
        defaultVersion
      ),
      MigrationInfo.fromEnv(
        process.env.GLOBAL_DOMAIN_LEGACY_MIGRATION_ID,
        process.env.GLOBAL_DOMAIN_LEGACY_VERSION
      ),
      MigrationInfo.fromEnv(
        process.env.GLOBAL_DOMAIN_UPGRADE_MIGRATION_ID,
        process.env.GLOBAL_DOMAIN_UPGRADE_VERSION
      ),
      processMigrationId(process.env.GLOBAL_DOMAIN_MIGRATE_FROM_MIGRATION_ID)
    );
  }

  containsUpgrade(): boolean {
    return this.upgrade != undefined;
  }

  isDefaultActive(): boolean {
    return this.active.migrationId == DefaultMigrationId;
  }

  isRunningMigration(): boolean {
    return (
      this.migratingFromActiveId != undefined &&
      this.migratingFromActiveId != this.active.migrationId
    );
  }

  migratingNodeConfig(): { migration: { id: DomainMigrationIndex; migrating: boolean } } {
    return {
      migration: {
        id: this.active.migrationId,
        migrating: this.isRunningMigration(),
      },
    };
  }
}

class MigrationInfo {
  migrationId: DomainMigrationIndex;
  version: CnChartVersion;

  constructor(migrationId: DomainMigrationIndex, version: CnChartVersion) {
    this.migrationId = migrationId;
    this.version = version;
  }

  static fromEnv(maybeIdValue?: string, maybeVersionValue?: string): MigrationInfo | undefined {
    const migrationId = processMigrationId(maybeIdValue);
    if (migrationId == undefined) {
      return undefined;
    } else {
      return new MigrationInfo(migrationId, processVersion(maybeVersionValue));
    }
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
  } else if (maybeValue == 'local') {
    return { type: 'local' };
  } else {
    return {
      type: 'remote',
      version: maybeValue,
    };
  }
}

export const DefaultMigrationId = 0;

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

export type DomainMigrationIndex = 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9;
