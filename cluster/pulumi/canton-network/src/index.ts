import * as pulumi from '@pulumi/pulumi';
import { Auth0ClusterConfig, Auth0Fetch, infraStack, requireEnv } from 'cn-pulumi-common';

import { installCluster } from './installCluster';
import { scheduleLoadGenerator } from './scheduleLoadGenerator';

async function auth0CacheAndInstallCluster(auth0Fetch: Auth0Fetch) {
  await auth0Fetch.loadAuth0Cache();

  const svc = await installCluster(auth0Fetch);

  await auth0Fetch.saveAuth0Cache();

  return svc;
}

async function main() {
  const auth0ClusterCfg = infraStack.requireOutput('auth0') as pulumi.Output<Auth0ClusterConfig>;
  const auth0FetchOutput = auth0ClusterCfg.cantonNetwork.apply(cfg => {
    cfg.auth0MgtClientSecret = requireEnv('AUTH0_CN_MANAGEMENT_API_CLIENT_SECRET');
    return new Auth0Fetch(cfg);
  });

  auth0FetchOutput.apply(async auth0Fetch => {
    // eslint-disable-next-line @typescript-eslint/no-floating-promises
    const cluster = await auth0CacheAndInstallCluster(auth0Fetch);

    scheduleLoadGenerator(auth0Fetch, cluster.validator1 ? [cluster.validator1] : []);
  });
}

// eslint-disable-next-line @typescript-eslint/no-floating-promises
main();
