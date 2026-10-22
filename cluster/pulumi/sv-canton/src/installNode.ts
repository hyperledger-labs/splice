// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  Auth0Client,
  CLUSTER_HOSTNAME,
  DecentralizedSynchronizerUpgradeConfig,
  DomainMigrationIndex,
  exactNamespace,
  imagePullSecretWithNonDefaultServiceAccount,
  supportsSvRunbookReset,
} from '@canton-network/splice-pulumi-common';
import {
  configForDSyncNode,
  configForSv,
  coreSvsToDeploy,
  dSyncMigrationConfigForNode,
  dSyncNodeNamesInSameDSync,
  InstalledMigrationSpecificSv,
  StaticSvConfig,
  staticSvConfigFromDSyncNodeConfig,
  sv1Config,
  svConfigs,
  svRunbookConfig,
} from '@canton-network/splice-pulumi-common-sv';

import { installCantonComponents } from './canton';

export async function installNode(
  migrationId: DomainMigrationIndex,
  auth0Client: Auth0Client,
  nodeName: string,
  nodeType: 'sv' | 'dsync'
): Promise<InstalledMigrationSpecificSv | undefined> {
  let nodeConfig: StaticSvConfig;
  if (nodeType === 'sv') {
    const svConfig = svConfigs.concat([svRunbookConfig]).find(config => {
      return config.nodeName === nodeName;
    });
    if (svConfig === undefined) {
      throw new Error(`No sv config found for ${nodeName}`);
    }
    nodeConfig = svConfig!;
  } else if (nodeType === 'dsync') {
    const dsyncNodeConfig = staticSvConfigFromDSyncNodeConfig(nodeName);
    if (dsyncNodeConfig === undefined) {
      throw new Error(`No dsync node config found for ${nodeName}`);
    }
    nodeConfig = {
      ...dsyncNodeConfig,
    };
  } else {
    throw new Error(`Unknown node type ${nodeType} for ${nodeName}`);
  }
  const isCoreSv = nodeConfig.nodeName !== svRunbookConfig.nodeName;
  const isFirstSv = nodeConfig.nodeName === sv1Config.nodeName;
  const isSvRunbook = nodeConfig.nodeName === svRunbookConfig.nodeName;

  const config =
    nodeType === 'sv' ? configForSv(nodeConfig.nodeName) : configForDSyncNode(nodeConfig.nodeName);
  const supportsReset = isSvRunbook ? supportsSvRunbookReset : undefined;

  // namespace lifecycle is managed by the main canton-network stack
  const xns = exactNamespace(nodeConfig.nodeName, true, true);

  const serviceAccountName = `sv-canton-migration-${migrationId}`;
  const imagePullDeps = imagePullSecretWithNonDefaultServiceAccount(xns, serviceAccountName);

  const migrationConfig =
    nodeType === 'sv'
      ? DecentralizedSynchronizerUpgradeConfig
      : dSyncMigrationConfigForNode(nodeName);

  const clusterHostname = CLUSTER_HOSTNAME;
  const configuredPeerAddresses =
    nodeType === 'dsync'
      ? dSyncNodeNamesInSameDSync(nodeName)
          .filter(dsyncNodeName => dsyncNodeName !== nodeName)
          .map(peerName => `sequencer-p2p-${migrationId}.${peerName}.${clusterHostname}`)
      : [];

  return await installCantonComponents(
    xns,
    migrationId,
    auth0Client,
    {
      ingressName: nodeConfig.ingressName,
      onboardingName: nodeConfig.onboardingName,
      auth0SvAppName: nodeConfig.auth0SvAppName,
      isFirstSv: isFirstSv,
      isCoreSv: isCoreSv,
      ...config,
    },
    migrationConfig,
    {
      nodeConfigs: {
        self: {
          ...nodeConfig.cometBft,
          nodeName: nodeConfig.nodeName,
        },
        sv1: {
          ...sv1Config.cometBft,
          nodeName: sv1Config.nodeName,
        },
        peers:
          isCoreSv && !isFirstSv
            ? coreSvsToDeploy
                .filter(config => config.nodeName !== nodeConfig.nodeName)
                .map(config => {
                  return {
                    ...config.cometBft,
                    nodeName: config.nodeName,
                  };
                })
            : [],
      },
    },
    nodeType,
    configuredPeerAddresses,
    nodeType === 'dsync', // autoInit is true for dsync nodes, false for sv nodes
    undefined,
    { dependsOn: imagePullDeps },
    supportsReset,
    serviceAccountName
  );
}
