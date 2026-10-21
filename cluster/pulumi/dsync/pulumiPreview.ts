// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { awaitAllOrThrowAllExceptions, ensureStackSettingsAreUpToDate } from '../pulumi';
import { runDSyncProjectForAllNodes } from './pulumi';

awaitAllOrThrowAllExceptions(
  runDSyncProjectForAllNodes(
    'preview',
    async (stack, nodeName) => {
      await ensureStackSettingsAreUpToDate(stack);
      const preview = await stack.preview({
        parallel: 128,
        diff: true,
        color: 'always',
      });
      console.log(`Previewing stack for ${nodeName}`);
      console.error(preview.stderr);
      console.log(preview.stdout);
      console.log(JSON.stringify(preview.changeSummary));
    },
    true
  )
).catch(err => {
  console.error('Failed to run preview');
  console.error(err);
  process.exit(1);
});
