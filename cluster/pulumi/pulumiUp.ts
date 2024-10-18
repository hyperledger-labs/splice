import {
  mustInstallValidator1,
  mustInstallSplitwell,
} from 'splice-pulumi-common-validator/src/validators';

import { awaitAllOrThrowAllExceptions, PulumiAbortController, stack, upStack } from './pulumi';

async function runCoreStacksUp() {
  const mainStack = await stack('canton-network', 'canton-network', true, {});
  const operations: Promise<void>[] = [];
  const abortController = new PulumiAbortController();
  await upStack(mainStack, abortController).then(async () => {
    if (mustInstallValidator1) {
      const validator1 = await stack('validator1', 'validator1', true, {});
      operations.push(upStack(validator1, abortController));
    }
    if (mustInstallSplitwell) {
      const splitwell = await stack('splitwell', 'splitwell', true, {});
      operations.push(upStack(splitwell, abortController));
    }
    awaitAllOrThrowAllExceptions(operations);
  });
}

runCoreStacksUp().catch(e => {
  console.error(e);
  process.exit(1);
});
