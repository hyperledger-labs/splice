// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  Auth0ClientType,
  getAuth0Config,
  Auth0Fetch,
} from '@lfdecentralizedtrust/splice-pulumi-common';

import { installClusterVersion } from './clusterVersion';
import { installCluster } from './installCluster';
import { scheduleLoadGenerator } from './scheduleLoadGenerator';

async function auth0CacheAndInstallCluster(auth0Fetch: Auth0Fetch) {
  await auth0Fetch.loadAuth0Cache();

  installClusterVersion();

  const cluster = await installCluster(auth0Fetch);

  await auth0Fetch.saveAuth0Cache();

  return cluster;
}

async function main() {
  const auth0FetchOutput = getAuth0Config(Auth0ClientType.MAINSTACK);

  auth0FetchOutput.apply(async auth0Fetch => {
    // eslint-disable-next-line @typescript-eslint/no-floating-promises
    await auth0CacheAndInstallCluster(auth0Fetch);

    scheduleLoadGenerator(auth0Fetch, []);
  });
}

// eslint-disable-next-line @typescript-eslint/no-floating-promises
main();
