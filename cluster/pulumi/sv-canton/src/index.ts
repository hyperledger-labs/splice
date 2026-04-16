// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';
import {
  Auth0ClientType,
  Auth0Fetch,
  config,
  getAuth0Config,
} from '@lfdecentralizedtrust/splice-pulumi-common';
import { InstalledMigrationSpecificSv } from '@lfdecentralizedtrust/splice-pulumi-common-sv';

import { installNode } from './installNode';

const migrationId = parseInt(config.requireEnv('SPLICE_MIGRATION_ID'))!;
const sv = config.requireEnv('SPLICE_SV');

async function auth0CacheAndInstallNode(
  auth0Fetch: Auth0Fetch
): Promise<InstalledMigrationSpecificSv | undefined> {
  await auth0Fetch.loadAuth0Cache();

  const node = await installNode(migrationId, sv, auth0Fetch);

  await auth0Fetch.saveAuth0Cache();

  return node;
}

function main(): pulumi.Output<InstalledMigrationSpecificSv | undefined> {
  const auth0FetchOutput = getAuth0Config(
    sv === 'sv' ? Auth0ClientType.RUNBOOK : Auth0ClientType.MAINSTACK
  );

  return auth0FetchOutput.apply(async auth0Fetch => await auth0CacheAndInstallNode(auth0Fetch));
}

const output = main();

export const participantDatabaseId = output.apply(node => node?.participant?.databaseId);
export const participantDatabaseSecretName = output.apply(
  node => node?.participant?.databaseSecretName
);
