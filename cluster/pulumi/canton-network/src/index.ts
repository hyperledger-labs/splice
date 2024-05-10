import * as pulumi from '@pulumi/pulumi';
import { Auth0ClusterConfig, Auth0Fetch, config, infraStack, isMainNet } from 'cn-pulumi-common';

import { installCluster } from './installCluster';
import { scheduleLoadGenerator } from './scheduleLoadGenerator';

async function auth0CacheAndInstallCluster(auth0Fetch: Auth0Fetch) {
  await auth0Fetch.loadAuth0Cache();

  const cluster = await installCluster(auth0Fetch);

  await auth0Fetch.saveAuth0Cache();

  return cluster;
}

async function main() {
  const auth0ClusterCfg = infraStack.requireOutput('auth0') as pulumi.Output<Auth0ClusterConfig>;
  let auth0FetchOutput: pulumi.Output<Auth0Fetch>;
  if (isMainNet) {
    if (!auth0ClusterCfg.mainnet) {
      throw new Error('missing mainNet auth0 output');
    }
    auth0FetchOutput = auth0ClusterCfg.mainnet.apply(cfg => {
      if (!cfg) {
        throw new Error('missing mainNet auth0 output');
      }
      cfg.auth0MgtClientSecret = config.requireEnv('AUTH0_MAIN_MANAGEMENT_API_CLIENT_SECRET');
      return new Auth0Fetch(cfg);
    });
  } else {
    if (!auth0ClusterCfg.cantonNetwork) {
      throw new Error('missing cantonNetwork auth0 output');
    }
    auth0FetchOutput = auth0ClusterCfg.cantonNetwork.apply(cfg => {
      if (!cfg) {
        throw new Error('missing cantonNetwork auth0 output');
      }
      cfg.auth0MgtClientSecret = config.requireEnv('AUTH0_CN_MANAGEMENT_API_CLIENT_SECRET');
      return new Auth0Fetch(cfg);
    });
  }

  auth0FetchOutput.apply(async auth0Fetch => {
    // eslint-disable-next-line @typescript-eslint/no-floating-promises
    const cluster = await auth0CacheAndInstallCluster(auth0Fetch);

    scheduleLoadGenerator(auth0Fetch, cluster.validator1 ? [cluster.validator1] : []);
  });
}

// eslint-disable-next-line @typescript-eslint/no-floating-promises
main();
