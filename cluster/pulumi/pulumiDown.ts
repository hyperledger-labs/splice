import { mustInstallValidator1 } from 'splice-pulumi-common-validator/src/validators';

import { downStack, stack } from './pulumi';

async function runCoreStackDown() {
  const mainStack = await stack('canton-network', 'canton-network', true, {});
  await downStack(mainStack);
  if (mustInstallValidator1) {
    const validator1 = await stack('validator1', 'validator1', true, {});
    await downStack(validator1);
  }
}

runCoreStackDown().catch(e => {
  console.error(e);
  process.exit(1);
});
