// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';
import { Auth0ClusterConfig, Auth0Fetch, config } from '@lfdecentralizedtrust/splice-pulumi-common';
import { infraStack } from '@lfdecentralizedtrust/splice-pulumi-common/src/stackReferences';

import { installNode } from './installNode';

async function auth0CacheAndInstallNode(auth0Fetch: Auth0Fetch) {
  await auth0Fetch.loadAuth0Cache();

  await installNode(auth0Fetch);

  await auth0Fetch.saveAuth0Cache();
}

// TODO(DACH-NY/canton-network-node#8008): Reduce duplication from sv-runbook stack
async function main() {
  const auth0ClusterCfg = infraStack.requireOutput('auth0') as pulumi.Output<Auth0ClusterConfig>;
  if (!auth0ClusterCfg.validatorRunbook) {
    throw new Error('missing validator runbook auth0 output');
  }
  const auth0FetchOutput = auth0ClusterCfg.validatorRunbook.apply(cfg => {
    if (!cfg) {
      throw new Error('missing validator runbook auth0 output');
    }
    cfg.auth0MgtClientSecret = config.requireEnv('AUTH0_VALIDATOR_MANAGEMENT_API_CLIENT_SECRET');
    return new Auth0Fetch(cfg);
  });

  auth0FetchOutput.apply(auth0Fetch => {
    // eslint-disable-next-line @typescript-eslint/no-floating-promises
    auth0CacheAndInstallNode(auth0Fetch);
  });
}

// eslint-disable-next-line @typescript-eslint/no-floating-promises
main();
