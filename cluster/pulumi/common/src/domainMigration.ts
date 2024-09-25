import { z } from 'zod';

import { CnChartVersion } from './artifacts';
import { config } from './config';
import { Config } from './config/configSchema';
import { MigrationInfoSchema, MigrationProvider } from './config/migrationSchema';

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
  public archived: MigrationInfo[];

  constructor(config: Config) {
    const synchronizerMigration = config.synchronizerMigration;
    this.active = synchronizerMigration.active;
    this.legacy = synchronizerMigration.legacy;
    this.upgrade = synchronizerMigration.upgrade;
    this.migratingFromActiveId = synchronizerMigration.active.migratingFrom;
    this.activeDatabaseId = synchronizerMigration.activeDatabaseId;
    this.archived = synchronizerMigration.archived || [];
  }

  runningMigrations(): MigrationInfo[] {
    return [this.active]
      .concat(this.legacy ? [this.legacy] : [])
      .concat(this.upgrade ? [this.upgrade] : []);
  }

  isStillRunning(id: DomainMigrationIndex): boolean {
    return this.runningMigrations().some(info => info.id == id);
  }

  isRunningMigration(): boolean {
    return this.migratingFromActiveId != undefined && this.migratingFromActiveId != this.active.id;
  }

  migratingNodeConfig(): {
    migration: { id: DomainMigrationIndex; migrating: boolean; legacyId?: DomainMigrationIndex };
  } {
    return {
      migration: {
        id: this.active.id,
        migrating: this.isRunningMigration(),
        legacyId: this.legacy?.id,
      },
    };
  }

  get allExternalMigrations(): MigrationInfo[] {
    return this.runningMigrations()
      .concat(this.archived)
      .filter(migration => migration.provider === MigrationProvider.EXTERNAL);
  }

  get allInternalMigrations(): MigrationInfo[] {
    return this.runningMigrations()
      .concat(this.archived)
      .filter(migration => migration.provider === MigrationProvider.INTERNAL);
  }

  get hasInternalRunningMigration(): boolean {
    return this.allInternalMigrations.some(migration => this.isStillRunning(migration.id));
  }
}

export type MigrationInfo = z.infer<typeof MigrationInfoSchema>;

export type DomainMigrationIndex = number;

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
      decentralizedSynchronizerUpgradeConfig.active.id,
      true,
      decentralizedSynchronizerUpgradeConfig.active.version
    ),
    legacyComponent:
      decentralizedSynchronizerUpgradeConfig.legacy != undefined
        ? component(
            decentralizedSynchronizerUpgradeConfig.legacy.id,
            false,
            decentralizedSynchronizerUpgradeConfig.legacy.version
          )
        : undefined,
    upgradeComponent:
      decentralizedSynchronizerUpgradeConfig.upgrade != undefined
        ? component(
            decentralizedSynchronizerUpgradeConfig.upgrade.id,
            false,
            decentralizedSynchronizerUpgradeConfig.upgrade.version
          )
        : undefined,
  };
}

export const DecentralizedSynchronizerUpgradeConfig: DecentralizedSynchronizerMigrationConfig =
  new DecentralizedSynchronizerMigrationConfig(config.configuration);

export const activeVersion = DecentralizedSynchronizerUpgradeConfig.active.version;
