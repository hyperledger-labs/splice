// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Output } from '@pulumi/pulumi';
import { DecentralizedSynchronizerMigrationConfig } from 'splice-pulumi-common';
import {
  CometBftNodeConfigs,
  CrossStackCometBftDecentralizedSynchronizerNode,
  CrossStackDecentralizedSynchronizerNode,
  InstalledMigrationSpecificSv,
  StaticCometBftConfigWithNodeName,
} from 'splice-pulumi-common-sv';
import { SvConfig } from 'splice-pulumi-common-sv/src/config';

export function buildCrossStackCantonDependencies(
  decentralizedSynchronizerMigrationConfig: DecentralizedSynchronizerMigrationConfig,
  cometbft: {
    name: string;
    onboardingName: string;
    nodeConfigs: {
      self: StaticCometBftConfigWithNodeName;
      sv1: StaticCometBftConfigWithNodeName;
      peers: StaticCometBftConfigWithNodeName[];
    };
  },
  svConfig: SvConfig
): InstalledMigrationSpecificSv {
  const activeMigrationId =
    decentralizedSynchronizerMigrationConfig.activeDatabaseId ||
    decentralizedSynchronizerMigrationConfig.active.id;

  return {
    decentralizedSynchronizer: decentralizedSynchronizerMigrationConfig.active.sequencer
      .enableBftSequencer
      ? new CrossStackDecentralizedSynchronizerNode(activeMigrationId, svConfig.ingressName)
      : new CrossStackCometBftDecentralizedSynchronizerNode(
          activeMigrationId,
          new CometBftNodeConfigs(activeMigrationId, cometbft.nodeConfigs).nodeIdentifier,
          svConfig.ingressName
        ),
    participant: {
      asDependencies: [],
      internalClusterAddress: Output.create(`participant-${activeMigrationId}`),
    },
  };
}
