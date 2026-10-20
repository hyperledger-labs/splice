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
  configForSv,
  InstalledMigrationSpecificSv,
  sv1Config,
  svConfigs,
  svRunbookConfig,
} from '@canton-network/splice-pulumi-common-sv';

import { installCantonComponents } from './canton';

export async function installNode(
  migrationId: DomainMigrationIndex,
  sv: string,
  auth0Client: Auth0Client
): Promise<InstalledMigrationSpecificSv | undefined> {
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

  const config = configForSv(nodeConfig.nodeName);
  const supportsReset = isSvRunbook ? supportsSvRunbookReset : undefined;

  // namespace lifecycle is managed by the main canton-network stack
  const xns = exactNamespace(nodeConfig.nodeName, true, true);

  const serviceAccountName = `sv-canton-migration-${migrationId}`;
  const imagePullDeps = imagePullSecretWithNonDefaultServiceAccount(xns, serviceAccountName);

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
    DecentralizedSynchronizerUpgradeConfig,
    undefined,
    { dependsOn: imagePullDeps },
    supportsReset,
    serviceAccountName
  );
}
