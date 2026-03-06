// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';
import {
  DecentralizedSynchronizerMigrationConfig,
  DomainMigrationIndex,
  MigrationInfo,
} from '@lfdecentralizedtrust/splice-pulumi-common';

import { CometBftNodeConfigs } from './cometBftNodeConfigs';
import { StaticCometBftConfigWithNodeName } from './cometbftConfig';
import {
  CrossStackCometBftDecentralizedSynchronizerNode,
  CrossStackDecentralizedSynchronizerNode,
  DecentralizedSynchronizerNode,
  InstalledMigrationSpecificSv,
  SvParticipant,
} from './decentralizedSynchronizerNode';

function buildDecentralizedSynchronizerNode(
  migrationInfo: MigrationInfo,
  migrationId: DomainMigrationIndex,
  cometbftNodeConfigs: {
    self: StaticCometBftConfigWithNodeName;
    sv1: StaticCometBftConfigWithNodeName;
    peers: StaticCometBftConfigWithNodeName[];
  },
  ingressName: string
): DecentralizedSynchronizerNode {
  return migrationInfo.sequencer.enableBftSequencer
    ? new CrossStackDecentralizedSynchronizerNode(migrationId, ingressName)
    : new CrossStackCometBftDecentralizedSynchronizerNode(
        migrationId,
        new CometBftNodeConfigs(migrationId, cometbftNodeConfigs).nodeIdentifier,
        ingressName
      );
}

/**
 * Represents the cross-stack Canton dependencies for an SV node, with a single top-level
 * participant and one decentralized synchronizer node per running migration.
 */
export class SynchronizerNodes {
  readonly participant: SvParticipant;
  readonly active: DecentralizedSynchronizerNode;
  readonly legacy?: DecentralizedSynchronizerNode;
  readonly upgrade?: DecentralizedSynchronizerNode;

  constructor(
    decentralizedSynchronizerMigrationConfig: DecentralizedSynchronizerMigrationConfig,
    cometbftNodeConfigs: {
      self: StaticCometBftConfigWithNodeName;
      sv1: StaticCometBftConfigWithNodeName;
      peers: StaticCometBftConfigWithNodeName[];
    },
    ingressName: string
  ) {
    const activeMigrationId =
      decentralizedSynchronizerMigrationConfig.activeDatabaseId ||
      decentralizedSynchronizerMigrationConfig.active.id;

    this.participant = {
      asDependencies: [],
      internalClusterAddress: decentralizedSynchronizerMigrationConfig.active
        .enableLogicalSynchronizerDeploymentMode
        ? pulumi.output('participant')
        : pulumi.output(`participant-${activeMigrationId}`),
    };

    this.active = buildDecentralizedSynchronizerNode(
      decentralizedSynchronizerMigrationConfig.active,
      activeMigrationId,
      cometbftNodeConfigs,
      ingressName
    );

    if (decentralizedSynchronizerMigrationConfig.legacy) {
      this.legacy = buildDecentralizedSynchronizerNode(
        decentralizedSynchronizerMigrationConfig.legacy,
        decentralizedSynchronizerMigrationConfig.legacy.id,
        cometbftNodeConfigs,
        ingressName
      );
    }

    if (decentralizedSynchronizerMigrationConfig.upgrade) {
      this.upgrade = buildDecentralizedSynchronizerNode(
        decentralizedSynchronizerMigrationConfig.upgrade,
        decentralizedSynchronizerMigrationConfig.upgrade.id,
        cometbftNodeConfigs,
        ingressName
      );
    }
  }

  /**
   * Returns the active migration as an InstalledMigrationSpecificSv for backward compatibility
   * with APIs that expect the old shape.
   */
  asInstalledMigrationSpecificSv(): InstalledMigrationSpecificSv {
    return {
      decentralizedSynchronizer: this.active,
      participant: this.participant,
    };
  }
}
