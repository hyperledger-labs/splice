// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  Auth0ClientType,
  Auth0Fetch,
  getAuth0Config,
} from '@lfdecentralizedtrust/splice-pulumi-common';

import { installNode } from './installNode';

async function auth0CacheAndInstallCluster(auth0Fetch: Auth0Fetch) {
  await auth0Fetch.loadAuth0Cache();

  const cluster = await installNode(auth0Fetch);

  await auth0Fetch.saveAuth0Cache();

  return cluster;
}

async function main() {
  const auth0FetchOutput = getAuth0Config(Auth0ClientType.MAINSTACK);

  auth0FetchOutput.apply(async auth0Fetch => {
    // eslint-disable-next-line @typescript-eslint/no-floating-promises
    await auth0CacheAndInstallCluster(auth0Fetch);
  });
}

main().catch(e => {
  console.error(e);
  process.exit(1);
});
