// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  Auth0Client,
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
  dSyncMigrationConfig,
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
  sv?: string,
  dsyncNode?: string
): Promise<InstalledMigrationSpecificSv | undefined> {
  if (!sv && !dsyncNode) {
    throw new Error('Either sv or dsyncNode must be specified');
  }

  let nodeConfig: StaticSvConfig;
  if (sv) {
    const svConfig = svConfigs.concat([svRunbookConfig]).find(config => {
      return config.nodeName === sv;
    });
    if (svConfig === undefined) {
      throw new Error(`No sv config found for ${sv}`);
    }
    nodeConfig = svConfig!;
  } else if (dsyncNode) {
    const dsyncNodeConfig = staticSvConfigFromDSyncNodeConfig(dsyncNode);
    if (dsyncNodeConfig === undefined) {
      throw new Error(`No dsync node config found for ${dsyncNode}`);
    }
    nodeConfig = {
      ...dsyncNodeConfig,
    };
  } else {
    throw new Error('Either sv or dsyncNode must be specified');
  }
  const isCoreSv = nodeConfig.nodeName !== svRunbookConfig.nodeName;
  const isFirstSv = nodeConfig.nodeName === sv1Config.nodeName;
  const isSvRunbook = nodeConfig.nodeName === svRunbookConfig.nodeName;

  const config = sv ? configForSv(nodeConfig.nodeName) : configForDSyncNode(nodeConfig.nodeName);
  const supportsReset = isSvRunbook ? supportsSvRunbookReset : undefined;

  // namespace lifecycle is managed by the main canton-network stack
  const xns = exactNamespace(nodeConfig.nodeName, true, true);

  const serviceAccountName = `sv-canton-migration-${migrationId}`;
  const imagePullDeps = imagePullSecretWithNonDefaultServiceAccount(xns, serviceAccountName);

  const migrationConfig = sv ? DecentralizedSynchronizerUpgradeConfig : dSyncMigrationConfig;

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
    sv ? 'sv' : 'dsync',
    undefined,
    { dependsOn: imagePullDeps },
    supportsReset,
    serviceAccountName
  );
}
