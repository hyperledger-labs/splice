import * as automation from '@pulumi/pulumi/automation';
import {
  mustInstallSplitwell,
  mustInstallValidator1,
} from 'splice-pulumi-common-validator/src/validators';

import {
  awaitAllOrThrowAllExceptions,
  Operation,
  operation,
  PulumiAbortController,
  refreshStack,
  stack,
} from './pulumi';

export async function runStacksCancel() {
  const mainStack = await stack('canton-network', 'canton-network', true, {});
  const operations: Operation[] = [];
  operations.push(cancelOperation(mainStack));
  if (mustInstallValidator1) {
    const validator1 = await stack('validator1', 'validator1', true, {});
    operations.push(cancelOperation(validator1));
  }
  if (mustInstallSplitwell) {
    const splitwell = await stack('splitwell', 'splitwell', true, {});
    operations.push(cancelOperation(splitwell));
  }
  const multiValidatorStack = await stack('multi-validator', 'multi-validator', true, {});
  operations.push(cancelOperation(multiValidatorStack));
  const svRunbookStack = await stack('sv-runbook', 'sv-runbook', true, {});
  operations.push(cancelOperation(svRunbookStack));
  const validatorRunbookStack = await stack('validator-runbook', 'validator-runbook', true, {});
  operations.push(cancelOperation(validatorRunbookStack));
  const deploymentStack = await stack('deployment', 'deployment', true, {});
  operations.push(cancelOperation(deploymentStack));
  const infraStack = await stack('infra', 'infra', true, {});
  operations.push(cancelOperation(infraStack));
  await awaitAllOrThrowAllExceptions(operations);
}

function cancelOperation(stack: automation.Stack): Operation {
  return operation(`cancel-${stack.name}`, stack.cancel());
}

export async function runStacksRefresh() {
  const mainStack = await stack('canton-network', 'canton-network', true, {});
  const operations: Operation[] = [];
  const abortController = new PulumiAbortController();
  operations.push(refreshOperation(mainStack, abortController));
  const validator1 = await stack('validator1', 'validator1', true, {});
  operations.push(refreshOperation(validator1, abortController));
  const splitwell = await stack('splitwell', 'splitwell', true, {});
  operations.push(refreshOperation(splitwell, abortController));
  const multiValidatorStack = await stack('multi-validator', 'multi-validator', true, {});
  operations.push(refreshOperation(multiValidatorStack, abortController));
  const svRunbookStack = await stack('sv-runbook', 'sv-runbook', true, {});
  operations.push(refreshOperation(svRunbookStack, abortController));
  const validatorRunbookStack = await stack('validator-runbook', 'validator-runbook', true, {});
  operations.push(refreshOperation(validatorRunbookStack, abortController));
  const deploymentStack = await stack('deployment', 'deployment', true, {});
  operations.push(refreshOperation(deploymentStack, abortController));
  await awaitAllOrThrowAllExceptions(operations);
}

function refreshOperation(
  stack: automation.Stack,
  abortController: PulumiAbortController
): Operation {
  return operation(`refresh-${stack.name}`, refreshStack(stack, abortController));
}
