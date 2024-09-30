import { mustInstallValidator1 } from 'splice-pulumi-common-validator/src/validators';

import { stack, upStack } from './pulumi';

async function runCoreStacksUp() {
  const mainStack = await stack('canton-network', 'canton-network', true, {});
  await upStack(mainStack);
  if (mustInstallValidator1) {
    const validator1 = await stack('validator1', 'validator1', true, {});
    await upStack(validator1);
  }
}

runCoreStacksUp().catch(e => {
  console.error(e);
  process.exit(1);
});
