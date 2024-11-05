import {
  mustInstallSplitwell,
  mustInstallValidator1,
} from 'splice-pulumi-common-validator/src/validators';

import { awaitAllOrThrowAllExceptions, stack } from './pulumi';

async function runCoreStackCancel() {
  const mainStack = await stack('canton-network', 'canton-network', true, {});
  const operations: Promise<void>[] = [];
  operations.push(mainStack.cancel());
  if (mustInstallValidator1) {
    const validator1 = await stack('validator1', 'validator1', true, {});
    operations.push(validator1.cancel());
  }
  if (mustInstallSplitwell) {
    const splitwell = await stack('splitwell', 'splitwell', true, {});
    operations.push(splitwell.cancel());
  }
  awaitAllOrThrowAllExceptions(operations);
}

runCoreStackCancel().catch(e => {
  console.error(e);
  process.exit(1);
});
