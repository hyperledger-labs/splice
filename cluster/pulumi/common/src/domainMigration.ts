export class GlobalDomainUpgradeConfig {
  prepareUpgrade: boolean;
  legacyMigrationId?: DomainMigrationIndex;
  activeMigrationId: DomainMigrationIndex;
  upgradeMigrationId?: DomainMigrationIndex;

  constructor(
    prepareUpgrade: boolean,
    activeMigrationId: DomainMigrationIndex,
    legacyMigrationId?: DomainMigrationIndex,
    upgradeMigrationId?: DomainMigrationIndex
  ) {
    this.prepareUpgrade = prepareUpgrade;
    this.legacyMigrationId = legacyMigrationId;
    this.activeMigrationId = activeMigrationId;
    this.upgradeMigrationId = upgradeMigrationId;
  }

  static fromEnv(): GlobalDomainUpgradeConfig {
    return new GlobalDomainUpgradeConfig(
      process.env.GLOBAL_DOMAIN_PREPARE_UPGRADE === 'true',
      processIndex(process.env.GLOBAL_DOMAIN_ACTIVE_MIGRATION_ID) || DefaultMigrationId,
      processIndex(process.env.GLOBAL_DOMAIN_LEGACY_MIGRATION_ID),
      processIndex(process.env.GLOBAL_DOMAIN_UPGRADE_MIGRATION_ID)
    );
  }

  containsUpgrade(): boolean {
    return this.upgradeMigrationId != undefined;
  }

  isDefaultActive(): boolean {
    return this.activeMigrationId == DefaultMigrationId;
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
  globalDomainUpgradeConfig: GlobalDomainUpgradeConfig,
  component: (migrationId: DomainMigrationIndex, isActive: boolean) => T
): T {
  if (globalDomainUpgradeConfig.upgradeMigrationId) {
    component(globalDomainUpgradeConfig.upgradeMigrationId, false);
  }
  if (globalDomainUpgradeConfig.legacyMigrationId) {
    component(globalDomainUpgradeConfig.legacyMigrationId, false);
  }
  if (globalDomainUpgradeConfig.activeMigrationId == DefaultMigrationId) {
    return component(DefaultMigrationId, true);
  } else {
    return component(globalDomainUpgradeConfig.activeMigrationId, true);
  }
}

export type DomainMigrationIndex = 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9;
