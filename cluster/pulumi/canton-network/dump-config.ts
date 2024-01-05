// Need to import this by path and not through the module, so the module is not
// initialized when we don't want it to (to avoid pulumi configs trying to being read here)
import { Auth0Config } from 'cn-pulumi-common';

import { SecretsFixtureMap, initDumpConfig } from '../common/src/dump-config-common';

initDumpConfig();

async function main() {
  const installCluster = await import('./src/installCluster');
  const auth0Cfg: Auth0Config = {
    appToClientId: {
      validator1: 'validator1-client-id',
      splitwell: 'splitwell-client-id',
      splitwell_validator: 'splitwell-validator-client-id',
      'sv-1': 'sv-1-client-id',
      'sv-2': 'sv-2-client-id',
      'sv-3': 'sv-3-client-id',
      'sv-4': 'sv-4-client-id',
      sv1_validator: 'sv1-validator-client-id',
      sv2_validator: 'sv2-validator-client-id',
      sv3_validator: 'sv3-validator-client-id',
      sv4_validator: 'sv4-validator-client-id',
    },
    namespaceToUiToClientId: {
      validator1: {
        wallet: 'validator1-wallet-ui-client-id',
        cns: 'validator1-cns-ui-client-id',
        splitwell: 'validator1-splitwell-ui-client-id',
      },
      splitwell: {
        wallet: 'splitwell-wallet-ui-client-id',
        cns: 'splitwell-cns-ui-client-id',
        splitwell: 'splitwell-splitwell-ui-client-id',
      },
      'sv-1': {
        wallet: 'sv-1-wallet-ui-client-id',
        cns: 'sv-1-cns-ui-client-id',
        sv: 'sv-1-sv-ui-client-id',
      },
      'sv-2': {
        wallet: 'sv-2-wallet-ui-client-id',
        cns: 'sv-2-cns-ui-client-id',
        sv: 'sv-2-sv-ui-client-id',
      },
      'sv-3': {
        wallet: 'sv-3-wallet-ui-client-id',
        cns: 'sv-3-cns-ui-client-id',
        sv: 'sv-3-sv-ui-client-id',
      },
      'sv-4': {
        wallet: 'sv-4-wallet-ui-client-id',
        cns: 'sv-4-cns-ui-client-id',
        sv: 'sv-4-sv-ui-client-id',
      },
    },
    appToApiAudience: {},
    appToClientAudience: {},
    auth0Domain: 'auth0Domain',
    auth0MgtClientId: 'auth0MgtClientId',
    auth0MgtClientSecret: 'auth0MgtClientSecret',
    fixedTokenCacheName: 'fixedTokenCacheName',
  };

  const secrets = new SecretsFixtureMap();

  installCluster.installCluster({
    getSecrets: () => Promise.resolve(secrets),
    /* eslint-disable @typescript-eslint/no-unused-vars */
    getClientAccessToken: (clientId: string, clientSecret: string, audience?: string) =>
      Promise.resolve('access_token'),
    getCfg: () => auth0Cfg,
  });
}

main();
