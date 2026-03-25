// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';
import {
  CLUSTER_HOSTNAME,
  DecentralizedSynchronizerMigrationConfig,
  MigrationInfo,
  pvcSuffix,
  standardStorageClassName,
} from '@lfdecentralizedtrust/splice-pulumi-common';

import { EnvVarConfig, SingleSvConfiguration } from './singleSvConfig';
import {
  CometbftSynchronizerNode,
  DecentralizedSynchronizerNode,
} from './synchronizer/decentralizedSynchronizerNode';
import { SynchronizerNodes } from './synchronizer/synchronizerNodes';

function localSynchronizerNodeValues(
  node: DecentralizedSynchronizerNode,
  nodeConfig: MigrationInfo,
  ingressName: string,
  cometBftGovernanceKey?: unknown,
  sequencerPruningConfig?: object
) {
  const useCantonBft = nodeConfig.sequencer.enableBftSequencer;
  return {
    sequencerAddress: node.namespaceInternalSequencerAddress,
    mediatorAddress: node.namespaceInternalMediatorAddress,
    sequencerPublicUrl: `https://sequencer-${nodeConfig.id}.${ingressName}.${CLUSTER_HOSTNAME}`,
    ...(useCantonBft ? { enableBftSequencer: true } : {}),
    ...(!useCantonBft
      ? {
          cometBftConfig: {
            enabled: true,
            connectionUri: pulumi.interpolate`http://${(node as unknown as CometbftSynchronizerNode).cometbftRpcServiceName}:26657`,
            ...(cometBftGovernanceKey ? { externalGovernanceKey: true } : {}),
          },
        }
      : {}),
    ...(sequencerPruningConfig ? { sequencerPruningConfig } : {}),
  };
}

export function valuesForSvApp(
  decentralizedSynchronizerMigrationConfig: DecentralizedSynchronizerMigrationConfig,
  config: SingleSvConfiguration & { cometBftGovernanceKey?: unknown; skipInitialization?: boolean },
  synchronizerNodes: SynchronizerNodes,
  ingressName: string
): {
  domain?: object;
  synchronizers?: object;
  additionalEnvVars: EnvVarConfig[];
  cometBFT?: object;
  pvc: object;
  permissionedSynchronizer?: boolean;
} {
  const bftSequencerConnectionEnvVars =
    !config.participant || config.participant.bftSequencerConnection
      ? []
      : [
          {
            name: 'ADDITIONAL_CONFIG_NO_BFT_SEQUENCER_CONNECTION',
            value: 'canton.sv-apps.sv.bft-sequencer-connection = false',
          },
        ];

  const mediatorPruningConfig = config.pruning?.mediator?.enabled
    ? [
        {
          name: 'ADDITIONAL_CONFIG_MEDIATOR_PRUNING',
          value: `canton.sv-apps.sv.local-synchronizer-nodes.current.mediator.pruning {
                    cron = "${config.pruning.mediator.cron}"
                    max-duration = "${config.pruning.mediator.maxDuration}"
                    retention = "${config.pruning.mediator.retentionPeriod}"
                  }`,
        },
      ]
    : [];
  const additionalEnvVars = (config.svApp?.additionalEnvVars || [])
    .concat(bftSequencerConnectionEnvVars)
    .concat(mediatorPruningConfig);

  const synchronizerValues: { synchronizers: object } = {
    synchronizers: {
      ...(config.skipInitialization !== undefined
        ? { skipInitialization: config.skipInitialization }
        : {}),
      current: localSynchronizerNodeValues(
        synchronizerNodes.active,
        decentralizedSynchronizerMigrationConfig.active,
        ingressName,
        config.cometBftGovernanceKey,
        config.pruning?.sequencer
      ),
      ...(synchronizerNodes.upgrade
        ? {
            successor: localSynchronizerNodeValues(
              synchronizerNodes.upgrade,
              decentralizedSynchronizerMigrationConfig.upgrade!,
              ingressName,
              config.cometBftGovernanceKey,
              config.pruning?.sequencer
            ),
          }
        : {}),
      ...(synchronizerNodes.legacy
        ? {
            legacy: localSynchronizerNodeValues(
              synchronizerNodes.legacy,
              decentralizedSynchronizerMigrationConfig.legacy!,
              ingressName,
              config.cometBftGovernanceKey,
              config.pruning?.sequencer
            ),
          }
        : {}),
    },
  };

  // if you add a top level field here that is an object make sure to handle merging it in the caller
  return {
    ...synchronizerValues,
    pvc: {
      volumeStorageClass: standardStorageClassName,
      volumeName: `sv-app-global-domain-migration-${pvcSuffix}`,
    },
    permissionedSynchronizer: config.svApp?.permissionedSynchronizer,
    additionalEnvVars,
  };
}
