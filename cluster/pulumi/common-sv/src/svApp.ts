// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';
import { CLUSTER_HOSTNAME, DecentralizedSynchronizerMigrationConfig, EnvVarConfig, envoyClientIpHeaderEnvVar, MigrationInfo, pvcSuffix, standardStorageClassName } from '@canton-network/splice-pulumi-common';



import { SingleSvConfiguration } from './singleSvConfig';
import { CometbftSynchronizerNode, DecentralizedSynchronizerNode } from './synchronizer/decentralizedSynchronizerNode';
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
          cometBFT: {
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
  const cantonBftPruningConfig = config.pruning?.cantonBft?.enabled
    ? [
        {
          name: 'ADDITIONAL_CONFIG_CANTON_BFT_PRUNING',
          value: `canton.sv-apps.sv.local-synchronizer-nodes.current.sequencer.canton-bft-pruning {
                    cron = "${config.pruning.cantonBft.cron}"
                    max-duration = "${config.pruning.cantonBft.maxDuration}"
                    retention = "${config.pruning.cantonBft.retentionPeriod}"
                  }`,
        },
      ]
    : [];
  const additionalPackagesToUnvetConfig =
    config.svApp?.additionalPackagesToUnvet &&
    Object.keys(config.svApp.additionalPackagesToUnvet).length > 0
      ? [
          {
            name: 'ADDITIONAL_CONFIG_ADDITIONAL_PACKAGES_TO_UNVET',
            value: Object.entries(config.svApp.additionalPackagesToUnvet)
              .map(
                ([pkgName, versions]) =>
                  `canton.sv-apps.sv.additional-packages-to-unvet.${JSON.stringify(pkgName)} = [${versions.map(v => JSON.stringify(v)).join(', ')}]`
              )
              .join('\n'),
          },
        ]
      : [];
  const additionalEnvVars = (config.svApp?.additionalEnvVars || [])
    .concat(bftSequencerConnectionEnvVars)
    .concat(mediatorPruningConfig)
    .concat(cantonBftPruningConfig)
    .concat(additionalPackagesToUnvetConfig)
    .concat([envoyClientIpHeaderEnvVar('canton.sv-apps.sv')]);

  if (config.svApp?.trafficBasedOnboarding) {
    additionalEnvVars.push({
      name: 'ADDITIONAL_CONFIG_TRAFFIC_BASED_ONBOARDING',
      value: `canton.sv-apps.sv.onboarding.initial-sv-operations-switch-over-times {\n  "no-featured-app-choice-context" = "0001-01-01T00:00:00Z"\n  "permissioned-synchronizer" = "0001-01-01T00:00:00Z"\n}`,
    });
  }

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
      ...(synchronizerNodes.additionalLegacy.length > 0
        ? {
            additionalLegacy: synchronizerNodes.additionalLegacy.map(node => {
              const info = decentralizedSynchronizerMigrationConfig.additionalLegacy.find(
                m => m.id === node.migrationId
              );
              if (!info) {
                throw new Error(
                  `No matching additionalLegacy migration info found for node with upgrade id ${node.migrationId}`
                );
              }
              return localSynchronizerNodeValues(
                node,
                info,
                ingressName,
                config.cometBftGovernanceKey,
                config.pruning?.sequencer
              );
            }),
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
    additionalEnvVars,
  };
}
