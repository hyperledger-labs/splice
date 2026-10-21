// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { PulumiAbortController, pulumiOptsWithPrefix } from '../pulumi';
import { runDSyncProjectForAllNodes } from './pulumi';

const abortController = new PulumiAbortController();

async function runUp() {
  runDSyncProjectForAllNodes(
    'up',
    async (stack, nodeName) => {
      console.log(`Updating stack for dsync node ${nodeName}`);
      const pulumiOpts = pulumiOptsWithPrefix(`[dsync=${nodeName}]`, abortController.signal);
      await stack.refresh(pulumiOpts).catch(err => {
        abortController.abort();
        throw err;
      });
      const result = await stack.up(pulumiOpts).catch(err => {
        abortController.abort();
        throw err;
      });
      console.log(`Updated stack for dsync node ${nodeName}`);
      console.log(JSON.stringify(result.summary));
    },
    false
  );
}

runUp().catch(() => {
  console.error('Failed to run up');
  process.exit(1);
});
