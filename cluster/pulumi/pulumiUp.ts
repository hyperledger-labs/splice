import { pulumiOptsWithPrefix, stack } from './pulumi';

async function runMainStackUp() {
  const mainStack = await stack('canton-network', 'canton-network', true, {});
  await mainStack.up(pulumiOptsWithPrefix(`[canton-network]`));
}

runMainStackUp().catch(e => {
  console.error(e);
  process.exit(1);
});
