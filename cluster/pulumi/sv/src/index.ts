// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  Auth0ClientType,
  config,
  getAuth0Config,
} from '@lfdecentralizedtrust/splice-pulumi-common';

import { installNode } from './installNode';

async function main() {
  const sv = config.requireEnv('SPLICE_SV');

  const auth0FetchOutput = getAuth0Config(Auth0ClientType.MAINSTACK);
  auth0FetchOutput.apply(async auth0Fetch => {
    await auth0Fetch.loadAuth0Cache();

    // defining resources in an apply callback is wrong...
    installNode(sv, auth0Fetch);

    await auth0Fetch.saveAuth0Cache();
  });
}

// eslint-disable-next-line @typescript-eslint/no-floating-promises
main();
