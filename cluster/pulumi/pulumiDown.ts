import {
  mustInstallSplitwell,
  mustInstallValidator1,
} from 'splice-pulumi-common-validator/src/validators';

import { downStack, PulumiAbortController, stack } from './pulumi';

async function runCoreStackDown() {
  const mainStack = await stack('canton-network', 'canton-network', true, {});
  const operations: Promise<void>[] = [];
  const abortController = new PulumiAbortController();
  operations.push(downStack(mainStack, abortController.signal).catch(e => abortController.abort("Aborting because mainStack failed")));
  if (mustInstallValidator1) {
    const validator1 = await stack('validator1', 'validator1', true, {});
    operations.push(downStack(validator1, abortController.signal).catch(e => abortController.abort("Aborting because validator1 failed")));
  }
  if (mustInstallSplitwell) {
    const splitwell = await stack('splitwell', 'splitwell', true, {});
    operations.push(downStack(splitwell, abortController.signal).catch(e => abortController.abort("Aborting because splitwell failed")));
  }
  const data = await Promise.allSettled(operations);
  const rejected = (data.find((res) => res.status === "rejected") as PromiseRejectedResult | undefined)?.reason
  if (rejected) {
    throw new Error(rejected);
  }
}

runCoreStackDown().catch(e => {
  console.error(e);
  process.exit(1);
});
