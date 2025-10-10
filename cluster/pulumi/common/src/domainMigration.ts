// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { z } from 'zod';

import { spliceConfig } from './config/config';
import { Config } from './config/configSchema';
import { MigrationInfoSchema } from './config/migrationSchema';

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

  get allMigrations(): MigrationInfo[] {
    return this.runningMigrations().concat(this.archived);
  }

  get highestMigrationId(): DomainMigrationIndex {
    return Math.max(...this.allMigrations.map(m => m.id));
  }
}

export type MigrationInfo = z.infer<typeof MigrationInfoSchema>;

export type DomainMigrationIndex = number;
export const DecentralizedSynchronizerUpgradeConfig: DecentralizedSynchronizerMigrationConfig =
  new DecentralizedSynchronizerMigrationConfig(spliceConfig.configuration);

export const activeVersion = DecentralizedSynchronizerUpgradeConfig.active.version;
export const allowDowngrade = DecentralizedSynchronizerUpgradeConfig.active.allowDowngrade;
