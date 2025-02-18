import * as automation from '@pulumi/pulumi/automation';
import {
  mustInstallSplitwell,
  mustInstallValidator1,
} from 'splice-pulumi-common-validator/src/validators';
import { startDownOperationsForCantonStacks } from 'sv-canton-pulumi-deployment/pulumiDown';

import {
  awaitAllOrThrowAllExceptions,
  downStack,
  PulumiAbortController,
  stack,
  Operation,
  operation,
} from './pulumi';

const abortController = new PulumiAbortController();

async function runStacksDown() {
  const mainStack = await stack('canton-network', 'canton-network', true, {});
  let operations: Operation[] = [];
  operations.push(downOperation(mainStack, abortController));
  const cantonDown = startDownOperationsForCantonStacks(abortController);
  operations = operations.concat(cantonDown);
  if (mustInstallValidator1) {
    const validator1 = await stack('validator1', 'validator1', true, {});
    operations.push(downOperation(validator1, abortController));
  }
  if (mustInstallSplitwell) {
    const splitwell = await stack('splitwell', 'splitwell', true, {});
    operations.push(downOperation(splitwell, abortController));
  }
  const multiValidatorStack = await stack('multi-validator', 'multi-validator', true, {});
  operations.push(downOperation(multiValidatorStack, abortController));
  const svRunbookStack = await stack('sv-runbook', 'sv-runbook', true, {});
  operations.push(downOperation(svRunbookStack, abortController));
  const validatorRunbookStack = await stack('validator-runbook', 'validator-runbook', true, {});
  operations.push(downOperation(validatorRunbookStack, abortController));
  const deploymentStack = await stack('deployment', 'deployment', true, {});
  operations.push(downOperation(deploymentStack, abortController));
  await awaitAllOrThrowAllExceptions(operations);
}

function downOperation(stack: automation.Stack, abortController: PulumiAbortController): Operation {
  return operation(`down-${stack.name}`, downStack(stack, abortController));
}

runStacksDown().catch(e => {
  console.error(e);
  process.exit(1);
});
