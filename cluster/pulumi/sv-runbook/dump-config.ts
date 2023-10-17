// Need to import this by path and not through the module, so the module is not
// initialized when we don't want it to (to avoid pulumi configs trying to being read here)
import { SecretsFixtureMap, initDumpConfig } from '../common/src/dump-config-common';

initDumpConfig();

async function main() {
  process.env.GCP_CLUSTER_BASENAME = 'svrun';
  process.env.TARGET_CLUSTER = 'svrun.network.com';
  process.env.ARTIFACTORY_USER = 'artie';
  process.env.ARTIFACTORY_PASSWORD = 's3cr3t';
  process.env.AUTH0_SV_MANAGEMENT_API_CLIENT_ID = 'mgmt';
  process.env.AUTH0_SV_MANAGEMENT_API_CLIENT_SECRET = 's3cr3t';

  const installNode = await import('./src/installNode');
  const auth0Cfg = await import('./src/auth0cfg');
  const utils = await import('./src/utils');
  const secrets = new SecretsFixtureMap();

  const authOClient = {
    getSecrets: () => Promise.resolve(secrets),
    /* eslint-disable @typescript-eslint/no-unused-vars */
    getClientAccessToken: (clientId: string, clientSecret: string, audience?: string) =>
      Promise.resolve('access_token'),
    getCfg: () => auth0Cfg.auth0Cfg,
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
