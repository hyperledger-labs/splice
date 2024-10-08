import {
  mustInstallValidator1,
  mustInstallSplitwell,
} from 'splice-pulumi-common-validator/src/validators';

import { stack, upStack } from './pulumi';

async function runCoreStacksUp() {
  const mainStack = await stack('canton-network', 'canton-network', true, {});
  const operations: Promise<void>[] = [];
  await upStack(mainStack);
  if (mustInstallValidator1) {
    const validator1 = await stack('validator1', 'validator1', true, {});
    operations.push(upStack(validator1));
  }
  if (mustInstallSplitwell) {
    const splitwell = await stack('splitwell', 'splitwell', true, {});
    operations.push(upStack(splitwell));
  }
  await Promise.all(operations);
}

runCoreStacksUp().catch(e => {
  console.error(e);
  process.exit(1);
});
