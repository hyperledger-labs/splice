// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as automation from '@pulumi/pulumi/automation';
import { runSvCantonForAllMigrations } from '@lfdecentralizedtrust/splice-pulumi-sv-canton/pulumi';

import { awaitAllOrThrowAllExceptions, Operation, stack } from './pulumi';
import { operation } from './pulumiOperations';

export async function runStacksCancel(): Promise<void> {
  const mainStack = await stack('canton-network', 'canton-network', true, {});
  console.error('Cancelling all the stacks');
  let operations: Operation[] = [];
  operations.push(cancelOperation(mainStack));
  const cantonStacksOperations = runSvCantonForAllMigrations(
    'cancel',
    stack => {
      return stack.cancel();
    },
    false,
    true
  );
  operations = operations.concat(cantonStacksOperations);
  const validator1 = await stack('validator1', 'validator1', true, {});
  operations.push(cancelOperation(validator1));
  const splitwell = await stack('splitwell', 'splitwell', true, {});
  operations.push(cancelOperation(splitwell));
  const multiValidatorStack = await stack('multi-validator', 'multi-validator', true, {});
  operations.push(cancelOperation(multiValidatorStack));
  const svRunbookStack = await stack('sv-runbook', 'sv-runbook', true, {});
  operations.push(cancelOperation(svRunbookStack));
  const validatorRunbookStack = await stack('validator-runbook', 'validator-runbook', true, {});
  operations.push(cancelOperation(validatorRunbookStack));
  const deploymentStack = await stack('deployment', 'deployment', true, {});
  operations.push(cancelOperation(deploymentStack));
  const operatorStack = await stack('operator', 'operator', true, {});
  operations.push(cancelOperation(operatorStack));
  const infraStack = await stack('infra', 'infra', true, {});
  operations.push(cancelOperation(infraStack));
  await awaitAllOrThrowAllExceptions(operations);
}

function cancelOperation(stack: automation.Stack): Operation {
  const opName = `cancel-${stack.name}`;
  console.error(`Starting operation ${opName}`);
  return operation(opName, stack.cancel());
}

runStacksCancel().catch(e => {
  console.error(e);
  process.exit(1);
});
