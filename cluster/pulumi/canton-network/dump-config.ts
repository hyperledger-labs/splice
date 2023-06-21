// Need to import this by path and not through the module, so the module is not
// initialized when we don't want it to (to avoid pulumi configs trying to being read here)
import { SecretsFixtureMap, initDumpConfig } from '../common/src/dump-config-common';

initDumpConfig();

async function main() {
  process.env.AUTH0_CN_MANAGEMENT_API_CLIENT_ID = 'mgmt';
  process.env.AUTH0_CN_MANAGEMENT_API_CLIENT_SECRET = 's3cr3t';

  const installCluster = await import('./src/installCluster');
  const auth0Cfg = await import('./src/auth0cfg');
  const secrets = new SecretsFixtureMap();

  installCluster.installCluster({
    getSecrets: () => Promise.resolve(secrets),
    /* eslint-disable @typescript-eslint/no-unused-vars */
    getClientAccessToken: (clientId: string, clientSecret: string, audience?: string) =>
      Promise.resolve('access_token'),
    getCfg: () => auth0Cfg.auth0Cfg,
  });
}

main();
