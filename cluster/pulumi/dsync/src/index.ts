// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Auth0Fetch, config, getAuth0ClusterConfig } from '@canton-network/splice-pulumi-common';

import { installNode } from './installNode';

const dsyncNode = config.requireEnv('SPLICE_DSYNC_NODE');


async function auth0CacheAndInstallNode(auth0Fetch: Auth0Fetch) {
  await auth0Fetch.loadAuth0Cache();

  await installNode(auth0Fetch, dsyncNode);

  await auth0Fetch.saveAuth0Cache();
}

async function main() {
  const auth0ClusterCfg = await getAuth0ClusterConfig();
  if (!auth0ClusterCfg.dSync) {
    throw new Error('dsync auth0 config is missing');
  }
  const auth0FetchOutput = auth0ClusterCfg.dSync.apply(cfg => {
    if (!cfg) {
      throw new Error('missing dsync auth0 output');
    }
    cfg.auth0MgtClientSecret = config.requireEnv('AUTH0_DSYNC_MANAGEMENT_API_CLIENT_SECRET');
    return new Auth0Fetch(cfg);
  });
  auth0FetchOutput.apply(auth0Fetch => {
    // eslint-disable-next-line @typescript-eslint/no-floating-promises
    auth0CacheAndInstallNode(auth0Fetch);
  });
}

// eslint-disable-next-line @typescript-eslint/no-floating-promises
main();
