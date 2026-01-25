// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  Auth0ClientType,
  Auth0Fetch,
  config,
  getAuth0Config,
} from '@lfdecentralizedtrust/splice-pulumi-common';

import { installNode } from './installNode';

const migrationId = parseInt(config.requireEnv('SPLICE_MIGRATION_ID'))!;
const sv = config.requireEnv('SPLICE_SV');

async function auth0CacheAndInstallNode(auth0Fetch: Auth0Fetch) {
  await auth0Fetch.loadAuth0Cache();

  const node = installNode(migrationId, sv, auth0Fetch);

  await auth0Fetch.saveAuth0Cache();

  return node;
}

async function main() {
  const auth0FetchOutput = getAuth0Config(
    sv === 'sv' ? Auth0ClientType.RUNBOOK : Auth0ClientType.MAINSTACK
  );

  auth0FetchOutput.apply(async auth0Fetch => await auth0CacheAndInstallNode(auth0Fetch));
}

// eslint-disable-next-line @typescript-eslint/no-floating-promises
main();
