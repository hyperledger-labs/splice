// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';
import {
  Auth0ClientType,
  Auth0Fetch,
  config,
  getAuth0Config,
} from '@canton-network/splice-pulumi-common';
import { InstalledMigrationSpecificSv } from '@canton-network/splice-pulumi-common-sv';

import { installNode } from './installNode';

const migrationId = parseInt(config.requireEnv('SPLICE_MIGRATION_ID'))!;
const sv = config.optionalEnv('SPLICE_SV');
const dsyncNode = config.optionalEnv('SPLICE_DSYNC_NODE');

if (!sv && !dsyncNode) {
  throw new Error('Either SPLICE_SV or SPLICE_DSYNC_NODE must be specified in the environment');
}
if (sv && dsyncNode) {
  throw new Error(`Only one of SPLICE_SV or SPLICE_DSYNC_NODE can be specified in the environment (found: sv='${sv}', dsyncNode='${dsyncNode}')`);
}

async function auth0CacheAndInstallNode(
  auth0Fetch: Auth0Fetch
): Promise<InstalledMigrationSpecificSv | undefined> {
  await auth0Fetch.loadAuth0Cache();

  const node = await installNode(migrationId, auth0Fetch, (sv || dsyncNode)!, sv ? 'sv' : 'dsync');

  await auth0Fetch.saveAuth0Cache();

  return node;
}

function main(): pulumi.Output<InstalledMigrationSpecificSv | undefined> {
  const auth0FetchOutput = getAuth0Config(
    sv ? (sv === 'sv' ? Auth0ClientType.RUNBOOK : Auth0ClientType.MAINSTACK) : Auth0ClientType.DSYNC
  );

  return auth0FetchOutput.apply(async auth0Fetch => await auth0CacheAndInstallNode(auth0Fetch));
}

main();
