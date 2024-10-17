import {
  mustInstallValidator1,
  mustInstallSplitwell,
} from 'splice-pulumi-common-validator/src/validators';

import { PulumiAbortController, stack, upStack } from './pulumi';

async function runCoreStacksUp() {
  const mainStack = await stack('canton-network', 'canton-network', true, {});
  const operations: Promise<void>[] = [];
  const abortController = new PulumiAbortController();
  await upStack(mainStack, abortController.signal).then(async () => {
    if (mustInstallValidator1) {
      const validator1 = await stack('validator1', 'validator1', true, {});
      operations.push(upStack(validator1, abortController.signal).catch(e => abortController.abort("Aborting because validator1 failed")));
    }
    if (mustInstallSplitwell) {
      const splitwell = await stack('splitwell', 'splitwell', true, {});
      operations.push(upStack(splitwell, abortController.signal).catch(e => abortController.abort("Aborting because splitwell failed")));
    }
    const data = await Promise.allSettled(operations);
    const rejected = (data.find((res) => res.status === "rejected") as PromiseRejectedResult | undefined)?.reason
    if (rejected) {
      throw new Error(rejected);
    }
  });
}

runCoreStacksUp().catch(e => {
  console.error(e);
  process.exit(1);
});
