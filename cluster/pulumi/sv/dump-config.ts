// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { allSvsToDeploy } from '@lfdecentralizedtrust/splice-pulumi-common-sv';

import {
  cantonNetworkAuth0Config,
  initDumpConfig,
  SecretsFixtureMap,
  svRunbookAuth0Config,
} from '../common/src/dump-config-common';

async function main() {
  await initDumpConfig();
  const installNode = await import('./src/installNode');
  const secrets = new SecretsFixtureMap();
  for (const sv of allSvsToDeploy) {
    installNode.installNode(sv.nodeName, {
      getSecrets: () => Promise.resolve(secrets),
      /* eslint-disable @typescript-eslint/no-unused-vars */
      getClientAccessToken: (clientId: string, clientSecret: string, audience: string) =>
        Promise.resolve('access_token'),
      getCfg: () => (sv.nodeName === 'sv' ? svRunbookAuth0Config : cantonNetworkAuth0Config),
      reuseNamespaceConfig: (fromNamespace: string, toNamespace: string) => {},
    });
  }
}

main().catch(err => {
  console.error(err);
  process.exit(1);
});
