import { awaitAllOrThrowAllExceptions, PulumiAbortController, refreshStack, stack } from './pulumi';

async function runCoreStackRefresh() {
  const mainStack = await stack('canton-network', 'canton-network', true, {});
  const operations: Promise<void>[] = [];
  const abortController = new PulumiAbortController();
  operations.push(refreshStack(mainStack, abortController));
  const validator1 = await stack('validator1', 'validator1', true, {});
  operations.push(refreshStack(validator1, abortController));
  const splitwell = await stack('splitwell', 'splitwell', true, {});
  operations.push(refreshStack(splitwell, abortController));
  await awaitAllOrThrowAllExceptions(operations);
}

runCoreStackRefresh().catch(e => {
  console.error(e);
  process.exit(1);
});
