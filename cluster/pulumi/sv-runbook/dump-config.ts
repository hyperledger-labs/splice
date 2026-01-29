// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  initDumpConfig,
  SecretsFixtureMap,
  svRunbookAuth0Config,
} from '../common/src/dump-config-common';

async function main() {
  await initDumpConfig();

  process.env.ARTIFACTORY_USER = 'artie';
  process.env.ARTIFACTORY_PASSWORD = 's3cr3t';

  const installNode = await import('./src/installNode');
  const secrets = new SecretsFixtureMap();
  // Need to import this directly to avoid initializing any configs before the mocks are initialized
  const { svRunbookConfig } = await import('@lfdecentralizedtrust/splice-pulumi-common-sv');

  const authOClient = {
    getSecrets: () => Promise.resolve(secrets),
    /* eslint-disable @typescript-eslint/no-unused-vars */
    getClientAccessToken: (clientId: string, clientSecret: string, audience: string) =>
      Promise.resolve('access_token'),
    getCfg: () => svRunbookAuth0Config,
    reuseNamespaceConfig: (fromNamespace: string, toNamespace: string) => {},
  };
  const buildSvAppConfig = (await import('./src/config')).buildSvAppConfig;
  const svAppConfig = buildSvAppConfig(false);
  const validatorAppConfig = {
    // sv runbook wallet user is always defined
    walletUserName: svRunbookConfig.validatorWalletUser!,
  };

  installNode.installNode(
    authOClient,
    svRunbookConfig.nodeName,
    svAppConfig,
    validatorAppConfig,
    () => Promise.resolve('dummy::partyId')
  );
}

main();
