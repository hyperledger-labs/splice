// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { z } from 'zod';

import { Config } from './config';
import { spliceConfig } from './config/config';
import { MigrationInfoSchema, SynchronizerMigrationConfig } from './config/migrationSchema';

export class DecentralizedSynchronizerMigrationConfig {
  // the current running migration, to which the ingresses point, and it's expected to be the active CN network
  // this is the only migration that contains the CN apps
  active: MigrationInfo;
  // if set then the canton components associated with this migration id are kept running, does not impact the CN apps
  legacy?: MigrationInfo;
  // additional legacy synchronizers that are kept around alongside `legacy`, e.g. when more than
  // one legacy synchronizer must be kept alive at a given point during an LSU.
  additionalLegacy: MigrationInfo[];
  // the next migration id that we are preparing
  // this is used to prepare the canton components for the upgrade
  upgrade?: MigrationInfo;
  // indicates that during this run we are actually migrating from this id to the active migration ID
  // used to configure  the CN apps for the migration
  migratingFromActiveId?: DomainMigrationIndex;
  activeDatabaseId?: DomainMigrationIndex;
  frozenMigrationId: number;
  public archived: MigrationInfo[];

  constructor(synchronizerMigration: SynchronizerMigrationConfig) {
    this.active = synchronizerMigration.active;
    this.legacy = synchronizerMigration.legacy;
    this.additionalLegacy = synchronizerMigration.additionalLegacy || [];
    this.upgrade = synchronizerMigration.upgrade;
    this.migratingFromActiveId = synchronizerMigration.active.migratingFrom;
    this.activeDatabaseId = synchronizerMigration.activeDatabaseId;
    this.archived = synchronizerMigration.archived || [];
    this.frozenMigrationId = synchronizerMigration.frozenMigrationId;
  }

  runningMigrations(): MigrationInfo[] {
    return [this.active]
      .concat(this.legacy ? [this.legacy] : [])
      .concat(this.additionalLegacy)
      .concat(this.upgrade ? [this.upgrade] : []);
  }

  usesCometbft(): boolean {
    return !this.runningMigrations().every(x => x.sequencer.enableBftSequencer);
  }

  isStillRunning(id: DomainMigrationIndex): boolean {
    return this.runningMigrations().some(info => info.id == id);
  }

  get allMigrations(): MigrationInfo[] {
    return this.runningMigrations().concat(this.archived);
  }

  get highestMigrationId(): DomainMigrationIndex {
    return Math.max(...this.allMigrations.map(m => m.id));
  }

  get activeMigrationId(): DomainMigrationIndex {
    return this.frozenMigrationId;
  }
}

export type MigrationInfo = z.infer<typeof MigrationInfoSchema>;

export type DomainMigrationIndex = number;
export const DecentralizedSynchronizerUpgradeConfig: DecentralizedSynchronizerMigrationConfig =
  new DecentralizedSynchronizerMigrationConfig(spliceConfig.configuration.synchronizerMigration);

export const activeVersion = DecentralizedSynchronizerUpgradeConfig.active.version;
