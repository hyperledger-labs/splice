// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { DeploySvRunbook, DeployValidatorRunbook } from '@lfdecentralizedtrust/splice-pulumi-common';
import {
  mustInstallSplitwell,
  mustInstallValidator1,
} from '@lfdecentralizedtrust/splice-pulumi-common-validator/src/validators';
import { runSvCantonForAllMigrations } from 'sv-canton-pulumi-deployment/pulumi';

import { awaitAllOrThrowAllExceptions, Operation, PulumiAbortController, stack } from './pulumi';
import { upOperation, upStack } from './pulumiOperations';

const abortController = new PulumiAbortController();

async function runAllStacksUp() {
  const mainStack = await stack('canton-network', 'canton-network', true, {});
  let operations: Operation[] = [];
  const mainStackUp = upStack(mainStack, abortController);
  operations.push({
    name: 'canton-network',
    promise: mainStackUp,
  });
  if (DeploySvRunbook) {
    const svRunbook = await stack('sv-runbook', 'sv-runbook', true, {});
    operations.push(upOperation(svRunbook, abortController));
  }
  if (DeployValidatorRunbook) {
    const validatorRunbook = await stack('validator-runbook', 'validator-runbook', true, {});
    operations.push(upOperation(validatorRunbook, abortController));
  }

  const cantonStacks = runSvCantonForAllMigrations(
    'up',
    stack => {
      return upStack(stack, abortController);
    },
    false
  );
  operations = operations.concat(cantonStacks);
  if (mustInstallValidator1) {
    const validator1 = await stack('validator1', 'validator1', true, {});
    operations.push(upOperation(validator1, abortController));
  }
  if (mustInstallSplitwell) {
    const splitwell = await stack('splitwell', 'splitwell', true, {});
    operations.push(upOperation(splitwell, abortController));
  }
  return awaitAllOrThrowAllExceptions(operations);
}

runAllStacksUp().catch(() => {
  console.error('Failed to run up');
  process.exit(1);
});
