// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
// Need to import this by path and not through the module, so the module is not
// initialized when we don't want it to (to avoid pulumi configs trying to being read here)
import { Auth0Config } from '@lfdecentralizedtrust/splice-pulumi-common';

import { SecretsFixtureMap, initDumpConfig } from '../common/src/dump-config-common';

async function main() {
  await initDumpConfig();
  // eslint-disable-next-line no-process-env
  process.env.SPLICE_VALIDATOR_RUNBOOK_VALIDATOR_NAME = 'validator-runbook';
  const installNode = await import('./src/installNode');
  const auth0Cfg: Auth0Config = {
    appToClientId: {
      validator: 'validator-client-id',
    },
    namespaceToUiToClientId: {
      validator: {
        wallet: 'wallet-client-id',
        cns: 'cns-client-id',
      },
    },
    appToApiAudience: {
      participant: 'https://ledger_api.example.com', // The Ledger API in the validator-test tenant
      validator: 'https://validator.example.com/api', // The Validator App API in the validator-test tenant
    },
    auth0Domain: 'auth0Domain',
    auth0MgtClientId: 'auth0MgtClientId',
    auth0MgtClientSecret: 'auth0MgtClientSecret',
    fixedTokenCacheName: 'fixedTokenCacheName',
  };
  const secrets = new SecretsFixtureMap();

  await installNode.installNode({
    getSecrets: () => Promise.resolve(secrets),
    /* eslint-disable @typescript-eslint/no-unused-vars */
    getClientAccessToken: (clientId: string, clientSecret: string, audience: string) =>
      Promise.resolve('access_token'),
    getCfg: () => auth0Cfg,
  });
}

main().catch(e => {
  console.error(e);
  process.exit(1);
});
