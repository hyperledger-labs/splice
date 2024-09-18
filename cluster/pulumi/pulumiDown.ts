import * as automation from '@pulumi/pulumi/automation';

import { pulumiOptsWithPrefix, stack } from './pulumi';

async function runMainStackDown() {
  const mainStack = await stack('canton-network', 'canton-network', true, {});
  try {
    await mainStack.destroy(pulumiOptsWithPrefix(`[canton-network]`));
  } catch (e) {
    if (e instanceof automation.ConcurrentUpdateError) {
      console.error(e);
      console.log('Stack is locked. Attempting to cancel the lock...');
      await mainStack.cancel();
      console.log('Lock canceled. Retrying destroy...');
      await mainStack.destroy(pulumiOptsWithPrefix(`[canton-network]`));
    }
  }
}

runMainStackDown().catch(e => {
  console.error(e);
  process.exit(1);
});
