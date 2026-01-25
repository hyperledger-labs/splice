// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { DecentralizedSynchronizerMigrationConfig } from '@lfdecentralizedtrust/splice-pulumi-common';
import {
  CometBftNodeConfigs,
  CrossStackCometBftDecentralizedSynchronizerNode,
  CrossStackDecentralizedSynchronizerNode,
  InstalledMigrationSpecificSv,
  sv1Config,
  svRunbookConfig,
} from '@lfdecentralizedtrust/splice-pulumi-common-sv';
import { Output } from '@pulumi/pulumi';

export function installCanton(
  onboardingName: string,
  decentralizedSynchronizerMigrationConfig: DecentralizedSynchronizerMigrationConfig
): InstalledMigrationSpecificSv {
  const activeMigrationId = decentralizedSynchronizerMigrationConfig.active.id;
  const nodeConfigs = {
    self: {
      ...svRunbookConfig.cometBft,
      nodeName: onboardingName,
    },
    sv1: {
      ...sv1Config?.cometBft,
      nodeName: sv1Config.nodeName,
    },
    peers: [],
  };
  return {
    decentralizedSynchronizer: decentralizedSynchronizerMigrationConfig.active.sequencer
      .enableBftSequencer
      ? new CrossStackDecentralizedSynchronizerNode(activeMigrationId, svRunbookConfig.ingressName)
      : new CrossStackCometBftDecentralizedSynchronizerNode(
          activeMigrationId,
          new CometBftNodeConfigs(activeMigrationId, nodeConfigs).nodeIdentifier,
          svRunbookConfig.ingressName
        ),
    participant: {
      asDependencies: [],
      internalClusterAddress: Output.create(`participant-${activeMigrationId}`),
    },
  };
}
