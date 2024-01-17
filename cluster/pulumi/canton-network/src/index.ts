import { Auth0Fetch, getAuth0Cfg, requireEnv } from 'cn-pulumi-common';

import { installCluster } from './installCluster';
import { scheduleLoadGenerator } from './scheduleLoadGenerator';

async function auth0CacheAndInstallCluster(auth0Fetch: Auth0Fetch) {
  await auth0Fetch.loadAuth0Cache();

  await installCluster(auth0Fetch);

  await auth0Fetch.saveAuth0Cache();
}

async function main() {
  const auth0ClusterCfg = getAuth0Cfg();
  const auth0FetchOutput = auth0ClusterCfg.cantonNetwork.apply(cfg => {
    cfg.auth0MgtClientSecret = requireEnv('AUTH0_CN_MANAGEMENT_API_CLIENT_SECRET');
    return new Auth0Fetch(cfg);
  });

  auth0FetchOutput.apply(auth0Fetch => {
    // eslint-disable-next-line @typescript-eslint/no-floating-promises
    auth0CacheAndInstallCluster(auth0Fetch);

    scheduleLoadGenerator(auth0Fetch);
  });
}

// eslint-disable-next-line @typescript-eslint/no-floating-promises
main();
