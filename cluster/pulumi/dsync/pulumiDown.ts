// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { PulumiAbortController } from '../pulumi';
import { runDSyncProjectForAllNodes } from './pulumi';
import { downStack } from '../pulumiOperations';

const abortController = new PulumiAbortController();

async function runDown() {
  runDSyncProjectForAllNodes(
    'down',
    stack => {
      return downStack(stack, abortController);
    },
    false
  )
}

runDown().catch(() => {
  console.error('Failed to run down');
  process.exit(1);
});
