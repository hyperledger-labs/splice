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
    const activeMigrationId = decentralizedSynchronizerMigrationConfig.active.id;

    this.participant = {
      asDependencies: [],
      internalClusterAddress: decentralizedSynchronizerMigrationConfig.active
        .enableLogicalSynchronizerDeploymentMode
        ? pulumi.output('participant')
        : pulumi.output(
            `participant-${decentralizedSynchronizerMigrationConfig.lsuEnabled ? decentralizedSynchronizerMigrationConfig.frozenMigrationId : activeMigrationId}`
          ),
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
}
