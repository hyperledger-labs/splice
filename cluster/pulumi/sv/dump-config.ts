// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { allSvsToDeploy } from '@canton-network/splice-pulumi-common-sv';

import {
  cantonNetworkAuth0Config,
  infraStackOutputsProvider,
  initDumpConfig,
  SecretsFixtureMap,
  svRunbookAuth0Config,
  withDumpConfigStack,
} from '../common/src/dump-config-common';

async function main() {
  await initDumpConfig({
    stackOutputsProvider: (project: string, stack: string) => {
      if (project === 'sv-canton') {
        return {
          participantDatabaseId: `${stack}-participant-db`,
          participantDatabaseSecretName: `${stack}-participant-db-secret`,
        };
      } else {
        return infraStackOutputsProvider(project, stack);
      }
    },
  });
  const installNode = await import('./src/installNode');
  const secrets = new SecretsFixtureMap();
  for (const sv of allSvsToDeploy) {
    await withDumpConfigStack(sv.nodeName, () =>
      installNode.installNode(sv.nodeName, {
        getSecrets: () => Promise.resolve(secrets),

        getClientAccessToken: (clientId: string, clientSecret: string, audience: string) =>
          Promise.resolve('access_token'),
        getCfg: () => (sv.nodeName === 'sv' ? svRunbookAuth0Config : cantonNetworkAuth0Config),
        reuseNamespaceConfig: (fromNamespace: string, toNamespace: string) => {},
      })
    );
  }
}

main().catch(e => {
  console.error(e.stack ?? e.message ?? e);
  process.exit(1);
});
