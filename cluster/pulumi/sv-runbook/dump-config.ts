// Need to import this by path and not through the module, so the module is not
// initialized when we don't want it to (to avoid pulumi configs trying to being read here)
import { SecretsFixtureMap, initDumpConfig } from '../common/src/dump-config-common';

initDumpConfig();

async function main() {

  process.env.GCP_CLUSTER_BASENAME = 'svrun';
  process.env.AUTH0_SV_MANAGEMENT_API_CLIENT_ID = 'mgmt-sv';
  process.env.AUTH0_SV_MANAGEMENT_API_CLIENT_SECRET = 's3cr3t-sv';
  process.env.TARGET_CLUSTER = 'svrun.network.com';
  process.env.ARTIFACTORY_USER = 'artie';
  process.env.ARTIFACTORY_PASSWORD = 's3cr3t';
  process.env.AUTH0_SV_MANAGEMENT_API_CLIENT_ID = 'mgmt';
  process.env.AUTH0_SV_MANAGEMENT_API_CLIENT_SECRET = 's3cr3t';

  const installNode = await import('./src/installNode');
  const auth0Cfg = await import('./src/auth0cfg');
  const secrets = new SecretsFixtureMap();

  installNode.installNode({
    getSecrets: () => Promise.resolve(secrets),
    /* eslint-disable @typescript-eslint/no-unused-vars */
    getClientAccessToken: (clientId: string, clientSecret: string) =>
      Promise.resolve('access_token'),
    getCfg: () => auth0Cfg.auth0Cfg,
  });
}

main();
