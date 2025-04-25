import { Output } from '@pulumi/pulumi';
import { DecentralizedSynchronizerMigrationConfig } from 'splice-pulumi-common';
import {
  CometBftNodeConfigs,
  CrossStackCometBftDecentralizedSynchronizerNode,
  CrossStackDecentralizedSynchronizerNode,
  InstalledMigrationSpecificSv,
  sv1Config,
  svRunbookConfig,
} from 'splice-pulumi-common-sv';

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
