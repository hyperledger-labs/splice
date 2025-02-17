import * as automation from '@pulumi/pulumi/automation';
import {
  mustInstallValidator1,
  mustInstallSplitwell,
} from 'splice-pulumi-common-validator/src/validators';

import {
  awaitAllOrThrowAllExceptions,
  operation,
  Operation,
  PulumiAbortController,
  stack,
  upStack,
} from './pulumi';

async function runCoreStacksUp() {
  const mainStack = await stack('canton-network', 'canton-network', true, {});
  const operations: Operation[] = [];
  const abortController = new PulumiAbortController();
  await upStack(mainStack, abortController).then(async () => {
    if (mustInstallValidator1) {
      const validator1 = await stack('validator1', 'validator1', true, {});
      operations.push(upOperation(validator1, abortController));
    }
    if (mustInstallSplitwell) {
      const splitwell = await stack('splitwell', 'splitwell', true, {});
      operations.push(upOperation(splitwell, abortController));
    }
    await awaitAllOrThrowAllExceptions(operations);
  });
}

function upOperation(stack: automation.Stack, abortController: PulumiAbortController): Operation {
  return operation(`up-${stack.name}`, upStack(stack, abortController));
}

runCoreStacksUp().catch(e => {
  console.error(e);
  process.exit(1);
});
