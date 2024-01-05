import * as pulumi from '@pulumi/pulumi';
import { Auth0ClusterConfig, Auth0Fetch, infraStack, requireEnv } from 'cn-pulumi-common';

import { installNode } from './installNode';

async function auth0CacheAndInstallNode(auth0Fetch: Auth0Fetch) {
  await auth0Fetch.loadAuth0Cache();

  await installNode(auth0Fetch);

  await auth0Fetch.saveAuth0Cache();
}

// TODO(#8008): Reduce duplication from sv-runbook stack
async function main() {
  const auth0ClusterCfg = infraStack.requireOutput('auth0') as pulumi.Output<Auth0ClusterConfig>;
  const auth0FetchOutput = auth0ClusterCfg.validatorRunbook.apply(cfg => {
    cfg.auth0MgtClientSecret = requireEnv('AUTH0_VALIDATOR_MANAGEMENT_API_CLIENT_SECRET');
    return new Auth0Fetch(cfg);
  });

  auth0FetchOutput.apply(auth0Fetch => {
    // eslint-disable-next-line @typescript-eslint/no-floating-promises
    auth0CacheAndInstallNode(auth0Fetch);
  });
}

// eslint-disable-next-line @typescript-eslint/no-floating-promises
main();
