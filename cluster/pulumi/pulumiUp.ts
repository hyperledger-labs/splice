import { DeploySvRunbook } from 'splice-pulumi-common';
import {
  mustInstallSplitwell,
  mustInstallValidator1,
} from 'splice-pulumi-common-validator/src/validators';
import { runSvCantonForAllMigrations } from 'sv-canton-pulumi-deployment/pulumi';

import { awaitAllOrThrowAllExceptions, Operation, PulumiAbortController, stack } from './pulumi';
import { upOperation, upStack } from './pulumiOperations';

const abortController = new PulumiAbortController();

async function runCantonNetworkAndSvCantonStacksUp() {
  const mainStack = await stack('canton-network', 'canton-network', true, {});
  let operations: Operation[] = [];
  const mainStackUp = upStack(mainStack, abortController);
  operations.push({
    name: 'canton-network',
    promise: mainStackUp,
  });
  const cantonStacks = runSvCantonForAllMigrations(
    'up',
    stack => {
      return upStack(stack, abortController);
    },
    false
  );
  operations = operations.concat(cantonStacks);
  return awaitAllOrThrowAllExceptions(operations);
}

async function runValidator1AndSplitwellStacksUp() {
  const operations: Operation[] = [];
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

async function runAllStacksUp() {
  await runCantonNetworkAndSvCantonStacksUp()
  return runValidator1AndSplitwellStacksUp()
}

runAllStacksUp().catch(() => {
  console.error('Failed to run up');
  process.exit(1);
});
