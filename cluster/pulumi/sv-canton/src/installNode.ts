// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  Auth0Client,
  DecentralizedSynchronizerUpgradeConfig,
  DomainMigrationIndex,
  exactNamespace,
  imagePullSecretWithNonDefaultServiceAccount,
  supportsSvRunbookReset,
} from '@lfdecentralizedtrust/splice-pulumi-common';
import {
  configForSv,
  coreSvsToDeploy,
  InstalledMigrationSpecificSv,
  sv1Config,
  svConfigs,
  svRunbookConfig,
} from '@lfdecentralizedtrust/splice-pulumi-common-sv';

import { installCantonComponents } from './canton';

export function installNode(
  migrationId: DomainMigrationIndex,
  sv: string,
  auth0Client: Auth0Client
): InstalledMigrationSpecificSv | undefined {
  const svConfig = svConfigs.concat([svRunbookConfig]).find(config => {
    return config.nodeName === sv;
  });
  if (svConfig === undefined) {
    throw new Error(`No sv config found for ${sv}`);
  }
  const nodeConfig = svConfig!;
  const isCoreSv = nodeConfig.nodeName !== svRunbookConfig.nodeName;
  const isFirstSv = nodeConfig.nodeName === sv1Config.nodeName;
  const isSvRunbook = nodeConfig.nodeName === svRunbookConfig.nodeName;

  // namespace lifecycle is managed by the main canton-network stack
  const xns = exactNamespace(nodeConfig.nodeName, true, true);

  const serviceAccountName = `sv-canton-migration-${migrationId}`;
  const imagePullDeps = imagePullSecretWithNonDefaultServiceAccount(xns, serviceAccountName);

  return installCantonComponents(
    xns,
    migrationId,
    auth0Client,
    {
      ingressName: nodeConfig.ingressName,
      onboardingName: nodeConfig.onboardingName,
      auth0SvAppName: nodeConfig.auth0SvAppName,
      isFirstSv: isFirstSv,
      isCoreSv: isCoreSv,
      ...configForSv(nodeConfig.nodeName),
    },
    DecentralizedSynchronizerUpgradeConfig,
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
    undefined,
    { dependsOn: imagePullDeps },
    isSvRunbook ? supportsSvRunbookReset : undefined,
    serviceAccountName
  );
}
