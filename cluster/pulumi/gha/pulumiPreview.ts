// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { awaitAllOrThrowAllExceptions, Operation, PulumiAbortController, stack } from '../pulumi';
import { previewOperation } from '../pulumiOperations';


async function runGhaPreview() {
  const operations: Operation[] = [];
  const abortController = new PulumiAbortController();
  const ghaStack = await stack('gha', 'gha', true, {});
  operations.push(previewOperation(ghaStack, abortController));
  await awaitAllOrThrowAllExceptions(operations);
}

runGhaPreview().catch(() => {
  console.error('Failed to run up');
  process.exit(1);
});
