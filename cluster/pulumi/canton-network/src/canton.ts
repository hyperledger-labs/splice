// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { DecentralizedSynchronizerMigrationConfig } from '@lfdecentralizedtrust/splice-pulumi-common';
import {
  CometBftNodeConfigs,
  CrossStackCometBftDecentralizedSynchronizerNode,
  CrossStackDecentralizedSynchronizerNode,
  InstalledMigrationSpecificSv,
  StaticCometBftConfigWithNodeName,
} from '@lfdecentralizedtrust/splice-pulumi-common-sv';
import { SvConfig } from '@lfdecentralizedtrust/splice-pulumi-common-sv/src/config';
import { Output } from '@pulumi/pulumi';

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
