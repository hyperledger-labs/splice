// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { awaitAllOrThrowAllExceptions, Operation, PulumiAbortController } from '../pulumi';
import { downStack } from '../pulumiOperations';
import { runSvProjectForAllSvs } from './pulumi';

export function startDownOperationsForSvStacks(
  abortController: PulumiAbortController
): Operation[] {
  return runSvProjectForAllSvs(
    'down',
    stack => {
      return downStack(stack, abortController);
    },
    false,
    true
  );
}

export async function downAllTheSvStacks(abortController: PulumiAbortController): Promise<void> {
  await awaitAllOrThrowAllExceptions(startDownOperationsForSvStacks(abortController));
}
