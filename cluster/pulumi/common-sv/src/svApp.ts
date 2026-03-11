// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';
import {
  CLUSTER_HOSTNAME,
  DecentralizedSynchronizerMigrationConfig,
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
  migrationId: number,
  ingressName: string,
  migrationConfig: DecentralizedSynchronizerMigrationConfig,
  cometBftGovernanceKey?: unknown,
  sequencerPruningConfig?: object
) {
  const useCantonBft = migrationConfig.active.sequencer.enableBftSequencer;
  return {
    sequencerAddress: node.namespaceInternalSequencerAddress,
    mediatorAddress: node.namespaceInternalMediatorAddress,
    sequencerPublicUrl: `https://sequencer-${migrationId}.${ingressName}.${CLUSTER_HOSTNAME}`,
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
  decentralizedSynchronizer: DecentralizedSynchronizerNode,
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
  const useCantonBft = decentralizedSynchronizerMigrationConfig.active.sequencer.enableBftSequencer;
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

  const lsuEnabled = decentralizedSynchronizerMigrationConfig.lsuEnabled;

  // When lsuEnabled, emit a synchronizers map
  // (current = active, successor = upgrade, legacy = legacy) instead of the single domain field.
  const synchronizerDomainValues: { domain?: object; synchronizers?: object } = lsuEnabled
    ? {
        synchronizers: {
          current: localSynchronizerNodeValues(
            synchronizerNodes.active,
            decentralizedSynchronizerMigrationConfig.active.id,
            ingressName,
            decentralizedSynchronizerMigrationConfig,
            config.cometBftGovernanceKey,
            config.pruning?.sequencer
          ),
          ...(synchronizerNodes.upgrade
            ? {
                successor: localSynchronizerNodeValues(
                  synchronizerNodes.upgrade,
                  decentralizedSynchronizerMigrationConfig.upgrade!.id,
                  ingressName,
                  decentralizedSynchronizerMigrationConfig,
                  config.cometBftGovernanceKey,
                  config.pruning?.sequencer
                ),
              }
            : {}),
          ...(synchronizerNodes.legacy
            ? {
                legacy: localSynchronizerNodeValues(
                  synchronizerNodes.legacy,
                  decentralizedSynchronizerMigrationConfig.legacy!.id,
                  ingressName,
                  decentralizedSynchronizerMigrationConfig,
                  config.cometBftGovernanceKey,
                  config.pruning?.sequencer
                ),
              }
            : {}),
        },
        ...(config.skipInitialization !== undefined
          ? { domain: { skipInitialization: config.skipInitialization } }
          : {}),
      }
    : {
        domain: {
          ...(config.pruning?.sequencer
            ? { sequencerPruningConfig: config.pruning.sequencer }
            : {}),
          ...(useCantonBft ? { enableBftSequencer: true } : {}),
          sequencerAddress: synchronizerNodes.active.namespaceInternalSequencerAddress,
          mediatorAddress: synchronizerNodes.active.namespaceInternalMediatorAddress,
          sequencerPublicUrl: `https://sequencer-${decentralizedSynchronizerMigrationConfig.active.id}.${ingressName}.${CLUSTER_HOSTNAME}`,
          ...(config.skipInitialization !== undefined
            ? { skipInitialization: config.skipInitialization }
            : {}),
        },
      };

  // if you add a top level field here that is an object make sure to handle merging it in the caller
  return {
    ...(useCantonBft
      ? {}
      : {
          cometBFT: {
            enabled: true,
            connectionUri: pulumi.interpolate`http://${(decentralizedSynchronizer as unknown as CometbftSynchronizerNode).cometbftRpcServiceName}:26657`,
            ...(config.cometBftGovernanceKey ? { externalGovernanceKey: true } : {}),
          },
        }),
    ...synchronizerDomainValues,
    pvc: {
      volumeStorageClass: standardStorageClassName,
      volumeName: `sv-app-global-domain-migration-${pvcSuffix}`,
    },
    permissionedSynchronizer: config.svApp?.permissionedSynchronizer,
    additionalEnvVars,
  };
}
