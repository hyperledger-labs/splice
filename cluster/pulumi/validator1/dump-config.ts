// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
// Need to import this by path and not through the module, so the module is not
// initialized when we don't want it to (to avoid pulumi configs trying to being read here)
import {
  SecretsFixtureMap,
  initDumpConfig,
  cantonNetworkAuth0Config,
} from '../common/src/dump-config-common';

async function main() {
  initDumpConfig();
  const installNode = await import('./src/installNode');

  const secrets = new SecretsFixtureMap();

  installNode.installNode({
    getSecrets: () => Promise.resolve(secrets),
    /* eslint-disable @typescript-eslint/no-unused-vars */
    getClientAccessToken: (clientId: string, clientSecret: string, audience: string) =>
      Promise.resolve('access_token'),
    getCfg: () => cantonNetworkAuth0Config,
    reuseNamespaceConfig: (fromNamespace: string, toNamespace: string) => {},
  });
}

main();
