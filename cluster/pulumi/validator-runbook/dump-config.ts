// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
// Need to import this by path and not through the module, so the module is not
// initialized when we don't want it to (to avoid pulumi configs trying to being read here)
import {
  Auth0Config,
  Auth0NamespaceConfig,
  NamespacedAuth0Configs,
} from '@canton-network/splice-pulumi-common';

import {
  SecretsFixtureMap,
  initDumpConfig,
  withDumpConfigStack,
} from '../common/src/dump-config-common';

async function main() {
  await initDumpConfig();
  const validatorName = 'validator-runbook';
  // eslint-disable-next-line no-process-env
  process.env.SPLICE_VALIDATOR_RUNBOOK_VALIDATOR_NAME = validatorName;
  const installNode = await import('./src/installNode');
  const namespaceAuth0Cfg: Auth0NamespaceConfig = {
    audiences: {
      ledgerApi: 'https://ledger_api.example.com',
      validatorApi: 'https://validator.example.com/api',
    },
    backendClientIds: {
      validator: 'validator-client-id',
    },
    uiClientIds: {
      wallet: 'wallet-client-id',
      cns: 'cns-client-id',
      walletGateway: 'wallet-gateway-client-id',
    },
  };
  const namespacedConfigs: NamespacedAuth0Configs = {};
  namespacedConfigs['validator'] = namespaceAuth0Cfg;
  const auth0Cfg: Auth0Config = {
    namespacedConfigs: namespacedConfigs,
    auth0Domain: 'auth0Domain',
    auth0MgtClientId: 'auth0MgtClientId',
    auth0MgtClientSecret: 'auth0MgtClientSecret',
    fixedTokenCacheName: 'fixedTokenCacheName',
  };
  const secrets = new SecretsFixtureMap();

  await withDumpConfigStack(validatorName, () =>
    installNode.installNode({
      getSecrets: () => Promise.resolve(secrets),
      getClientAccessToken: (clientId: string, clientSecret: string, audience: string) =>
        Promise.resolve('access_token'),
      getCfg: () => auth0Cfg,
      reuseNamespaceConfig: (fromNamespace: string, toNamespace: string) => {},
    })
  );
}

main().catch(e => {
  console.error(e.stack ?? e.message ?? e);
  process.exit(1);
});
