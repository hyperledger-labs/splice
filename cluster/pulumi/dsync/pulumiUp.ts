// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { runSvProjectForSvs } from '@canton-network/splice-pulumi-sv/pulumi';

import { awaitAllOrThrowAllExceptions, Operation, PulumiAbortController, stack } from '../pulumi';

const abortController = new PulumiAbortController();

async function runUp() {
}

runUp().catch(() => {
  console.error('Failed to run up');
  process.exit(1);
});
