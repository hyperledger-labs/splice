import * as automation from '@pulumi/pulumi/automation';
import util from 'node:util';
import {
  mustInstallValidator1,
  mustInstallSplitwell,
} from 'splice-pulumi-common-validator/src/validators';
import { runSvCantonForAllMigrations } from 'sv-canton-pulumi-deployment/pulumi';

import {
  awaitAllOrThrowAllExceptions,
  operation,
  Operation,
  PulumiAbortController,
  pulumiOptsWithPrefix,
  stack,
} from './pulumi';

async function runAllStacksUp() {
  const mainStack = await stack('canton-network', 'canton-network', true, {});
  let operations: Operation[] = [];
  const abortController = new PulumiAbortController();
  const mainStackUp = upStack(mainStack, abortController);
  operations.push({
    name: 'canton-network',
    promise: mainStackUp,
  });
  const cantonStacks = runSvCantonForAllMigrations(stack => {
    return upStack(stack, abortController);
  }, false);
  operations = operations.concat(cantonStacks);
  try {
    await mainStackUp.then(async () => {
      if (mustInstallValidator1) {
        const validator1 = await stack('validator1', 'validator1', true, {});
        operations.push(upOperation(validator1, abortController));
      }
      if (mustInstallSplitwell) {
        const splitwell = await stack('splitwell', 'splitwell', true, {});
        operations.push(upOperation(splitwell, abortController));
      }
      return;
    });
  } catch (e) {
    console.error('Failed to update main stack');
  } finally {
    console.error('Finalizing update');
    await awaitAllOrThrowAllExceptions(operations);
  }
}

function upOperation(stack: automation.Stack, abortController: PulumiAbortController): Operation {
  return operation(`up-${stack.name}`, upStack(stack, abortController));
}

export async function upStack(
  stack: automation.Stack,
  abortController: PulumiAbortController
): Promise<void> {
  const name = stack.name;
  const result = await stack
    .up(pulumiOptsWithPrefix(`[${name}]`, abortController.signal))
    .catch(e => {
      console.error('Aborting because of exception', e);
      throw e;
    });
  console.log(
    util.inspect(result.summary, {
      colors: true,
      depth: null,
      maxStringLength: null,
    })
  );
}

runAllStacksUp().catch(() => {
  console.error('Failed to run up');
  process.exit(1);
});
