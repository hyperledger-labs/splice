// Need to import this by path and not through the module, so the module is not
// initialized when we don't want it to (to avoid pulumi configs trying to being read here)
import { Auth0Config } from 'cn-pulumi-common';

import { SecretsFixtureMap, initDumpConfig } from '../common/src/dump-config-common';

initDumpConfig();

async function main() {
  process.env.GCP_CLUSTER_BASENAME = 'svrun';
  process.env.TARGET_CLUSTER = 'svrun.network.com';
  process.env.ARTIFACTORY_USER = 'artie';
  process.env.ARTIFACTORY_PASSWORD = 's3cr3t';

  const installNode = await import('./src/installNode');
  const auth0Cfg: Auth0Config = {
    appToClientId: {
      sv: 'sv-client-id',
      validator: 'validator-client-id',
    },
    namespaceToUiToClientId: {
      sv: {
        wallet: 'wallet-client-id',
        cns: 'cns-client-id',
        sv: 'sv-client-id',
      },
    },
    appToApiAudience: {
      participant: 'https://ledger_api.example.com', // The Ledger API in the sv-test tenant
      sv: 'https://sv.example.com/api', // The SV App API in the sv-test tenant
      validator: 'https://validator.example.com/api', // The Validator App API in the sv-test tenant
    },

    appToClientAudience: {
      sv: 'https://ledger_api.example.com',
      validator: 'https://ledger_api.example.com',
    },
    auth0Domain: 'auth0Domain',
    auth0MgtClientId: 'auth0MgtClientId',
    auth0MgtClientSecret: 'auth0MgtClientSecret',
    fixedTokenCacheName: 'fixedTokenCacheName',
  };
  const utils = await import('./src/utils');
  const secrets = new SecretsFixtureMap();

  const authOClient = {
    getSecrets: () => Promise.resolve(secrets),
    /* eslint-disable @typescript-eslint/no-unused-vars */
    getClientAccessToken: (clientId: string, clientSecret: string, audience?: string) =>
      Promise.resolve('access_token'),
    getCfg: () => auth0Cfg,
  };
  const svAppConfig = {
    onboardingName: utils.SV_NAME,
    cometBftConnectionUri: 'http://cometbft-cometbft-rpc:26657',
  };
  const validatorAppConfig = {
    walletUserName: utils.validatorWalletUserName,
  };

  installNode.installNode(authOClient, utils.SV_NAMESPACE, svAppConfig, validatorAppConfig);
}

main();
