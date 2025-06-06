// Need to import this by path and not through the module, so the module is not
// initialized when we don't want it to (to avoid pulumi configs trying to being read here)
import { Auth0Config } from 'splice-pulumi-common';

import { SecretsFixtureMap, initDumpConfig } from '../common/src/dump-config-common';

async function main() {
  await initDumpConfig();
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

    appToClientAudience: {
      validator: 'https://ledger_api.example.com',
    },
    auth0Domain: 'auth0Domain',
    auth0MgtClientId: 'auth0MgtClientId',
    auth0MgtClientSecret: 'auth0MgtClientSecret',
    fixedTokenCacheName: 'fixedTokenCacheName',
  };
  const secrets = new SecretsFixtureMap();

  installNode.installNode({
    getSecrets: () => Promise.resolve(secrets),
    /* eslint-disable @typescript-eslint/no-unused-vars */
    getClientAccessToken: (clientId: string, clientSecret: string, audience?: string) =>
      Promise.resolve('access_token'),
    getCfg: () => auth0Cfg,
  });
}

main();
