export class GlobalDomainMigrationConfig {
  prepareUpgrade: boolean;
  // if set then the canton components associated with this migration id are kept running, does not impact the CN apps
  legacyMigrationId?: DomainMigrationIndex;
  // the current running migration, to which the ingresses point, and it's expected to be the active CN network
  // this is the only migration that contains the CN apps
  activeMigrationId: DomainMigrationIndex;
  // the next migration id that we are preparing
  // this is used to prepare the canton components for the upgrade
  upgradeMigrationId?: DomainMigrationIndex;
  // indicates that during this run we are actually migrating from this id to the `activeMigrationId`
  // used to configure  the CN apps for the migration
  migratingFromActiveId?: DomainMigrationIndex;

  constructor(
    prepareUpgrade: boolean,
    activeMigrationId: DomainMigrationIndex,
    legacyMigrationId?: DomainMigrationIndex,
    upgradeMigrationId?: DomainMigrationIndex,
    migratingFromActiveId?: DomainMigrationIndex
  ) {
    this.prepareUpgrade = prepareUpgrade;
    this.legacyMigrationId = legacyMigrationId;
    this.activeMigrationId = activeMigrationId;
    this.upgradeMigrationId = upgradeMigrationId;
    this.migratingFromActiveId = migratingFromActiveId;
  }

  // TODO(#10074) read this from current deployment if available
  static fromEnv(): GlobalDomainMigrationConfig {
    return new GlobalDomainMigrationConfig(
      process.env.GLOBAL_DOMAIN_PREPARE_UPGRADE === 'true',
      processIndex(process.env.GLOBAL_DOMAIN_ACTIVE_MIGRATION_ID) || DefaultMigrationId,
      processIndex(process.env.GLOBAL_DOMAIN_LEGACY_MIGRATION_ID),
      processIndex(process.env.GLOBAL_DOMAIN_UPGRADE_MIGRATION_ID),
      processIndex(process.env.GLOBAL_DOMAIN_MIGRATE_FROM_MIGRATION_ID)
    );
  }

  containsUpgrade(): boolean {
    return this.upgradeMigrationId != undefined;
  }

  isDefaultActive(): boolean {
    return this.activeMigrationId == DefaultMigrationId;
  }

  isRunningMigration(): boolean {
    return (
      this.migratingFromActiveId != undefined &&
      this.migratingFromActiveId != this.activeMigrationId
    );
  }

  validatorMigrationConfig(): { migration: { id: DomainMigrationIndex; migrating: boolean } } {
    return {
      migration: {
        id: this.activeMigrationId,
        migrating: this.isRunningMigration(),
      },
    };
  }
}

function processIndex(maybeValue?: string) {
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

export const DefaultMigrationId = 0;

export function installMigrationIdSpecificComponent<T>(
  globalDomainUpgradeConfig: GlobalDomainMigrationConfig,
  component: (migrationId: DomainMigrationIndex, isActive: boolean) => T
): {
  activeComponent: T;
  legacyComponent?: T;
  upgradeComponent?: T;
} {
  return {
    activeComponent: component(globalDomainUpgradeConfig.activeMigrationId, true),
    legacyComponent:
      globalDomainUpgradeConfig.legacyMigrationId != undefined
        ? component(globalDomainUpgradeConfig.legacyMigrationId, false)
        : undefined,
    upgradeComponent:
      globalDomainUpgradeConfig.upgradeMigrationId != undefined
        ? component(globalDomainUpgradeConfig.upgradeMigrationId, false)
        : undefined,
  };
}

export type DomainMigrationIndex = 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9;
