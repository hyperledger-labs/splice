import {
  mustInstallSplitwell,
  mustInstallValidator1,
} from 'splice-pulumi-common-validator/src/validators';

import { awaitAllOrThrowAllExceptions, downStack, PulumiAbortController, stack } from './pulumi';

async function runCoreStackDown() {
  const mainStack = await stack('canton-network', 'canton-network', true, {});
  const operations: Promise<void>[] = [];
  const abortController = new PulumiAbortController();
  operations.push(downStack(mainStack, abortController));
  if (mustInstallValidator1) {
    const validator1 = await stack('validator1', 'validator1', true, {});
    operations.push(downStack(validator1, abortController));
  }
  if (mustInstallSplitwell) {
    const splitwell = await stack('splitwell', 'splitwell', true, {});
    operations.push(downStack(splitwell, abortController));
  }
  await awaitAllOrThrowAllExceptions(operations);
}

runCoreStackDown().catch(e => {
  console.error(e);
  process.exit(1);
});
