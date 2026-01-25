// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { ClusterBasename } from '@lfdecentralizedtrust/splice-pulumi-common/src/config/gcpConfig';

import { awaitAllOrThrowAllExceptions, Operation, PulumiAbortController, stack } from '../pulumi';
import { downStack } from '../pulumiOperations';

export async function startDownOperationsForValidatorStacks(
  abortController: PulumiAbortController
): Promise<Operation[]> {
  const validatorRunbookStack = await stack('validator-runbook', 'validator-runbook', true, {});
  const allValidatorStacks = await validatorRunbookStack.workspace.listStacks({
    all: false,
  });
  const allValidatorsForCluster = allValidatorStacks.filter(stack =>
    stack.name.endsWith(ClusterBasename)
  );
  console.error(`Resetting validators ${JSON.stringify(allValidatorsForCluster)}`);
  return allValidatorsForCluster.map(stackSummary => {
    return {
      name: `down-${stackSummary.name}`,
      promise: stack(
        'validator-runbook',
        stackSummary.name.slice(0, stackSummary.name.length - (ClusterBasename.length + 1)),
        true,
        {}
        // eslint-disable-next-line promise/prefer-await-to-then
      ).then(stack => downStack(stack, abortController)),
    };
  });
}

export async function downAllTheValidatorsStacks(
  abortController: PulumiAbortController
): Promise<void> {
  const operations = await startDownOperationsForValidatorStacks(abortController);
  await awaitAllOrThrowAllExceptions(operations);
}
