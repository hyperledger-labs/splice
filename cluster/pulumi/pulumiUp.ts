// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { DeploySvRunbook } from '@lfdecentralizedtrust/splice-pulumi-common';
import {
  mustInstallSplitwell,
  mustInstallValidator1,
} from '@lfdecentralizedtrust/splice-pulumi-common-validator/src/validators';
import { runSvCantonForAllMigrations } from '@lfdecentralizedtrust/splice-pulumi-sv-canton/pulumi';
import {
  runSvProjectForAllSvs,
  runSvProjectForAllSvsIfLsu,
} from '@lfdecentralizedtrust/splice-pulumi-sv/pulumi';

import { awaitAllOrThrowAllExceptions, Operation, PulumiAbortController, stack } from './pulumi';
import { upOperation, upStack } from './pulumiOperations';
import { runAllValidatorsUp } from './validator-runbook/pulumiUp';

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
  const validators = runAllValidatorsUp(abortController);
  operations = operations.concat(validators);
  const cantonStacks = runSvCantonForAllMigrations(
    'up',
    stack => {
      return upStack(stack, abortController);
    },
    false
  );
  operations = operations.concat(cantonStacks);
  const svStacks = runSvProjectForAllSvsIfLsu(
    'up',
    stack => {
      return upStack(stack, abortController);
    },
    false
  );
  operations = operations.concat(svStacks);
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

runAllStacksUp().catch((err: unknown) => {
  console.error(
    `\nPulumi up finished with errors. See the summary above for details.\n` +
      (err instanceof Error ? err.message : String(err))
  );
  process.exit(1);
});
