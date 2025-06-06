import { runSvCantonForSvs } from 'sv-canton-pulumi-deployment/pulumi';

import { awaitAllOrThrowAllExceptions, Operation, PulumiAbortController, stack } from '../pulumi';
import { upOperation, upStack } from '../pulumiOperations';

const abortController = new PulumiAbortController();

async function runRunbookUp() {
  let operations: Operation[] = [];
  const svRunbookStack = await stack('sv-runbook', 'sv-runbook', true, {});
  operations.push(upOperation(svRunbookStack, abortController));
  const cantonStacks = runSvCantonForSvs(
    ['sv'],
    'up',
    stack => {
      return upStack(stack, abortController);
    },
    false
  );
  operations = operations.concat(cantonStacks);
  await awaitAllOrThrowAllExceptions(operations);
}

runRunbookUp().catch(() => {
  console.error('Failed to run up');
  process.exit(1);
});
