import { PulumiAbortController, refreshStack, stack } from './pulumi';

async function runCoreStackRefresh() {
  const mainStack = await stack('canton-network', 'canton-network', true, {});
  const operations: Promise<void>[] = [];
  const abortController = new PulumiAbortController();
  operations.push(refreshStack(mainStack, abortController.signal).catch(e => abortController.abort("Aborting because mainStack failed")));
  const validator1 = await stack('validator1', 'validator1', true, {});
  operations.push(refreshStack(validator1, abortController.signal).catch(e => abortController.abort("Aborting because validator1 failed")));
  const splitwell = await stack('splitwell', 'splitwell', true, {});
  operations.push(refreshStack(splitwell, abortController.signal).catch(e => abortController.abort("Aborting because splitwell failed")));
  const data = await Promise.allSettled(operations);

  const rejected = (data.find((res) => res.status === "rejected") as PromiseRejectedResult | undefined)?.reason
  if (rejected) {
    throw new Error(rejected);
  }
}

runCoreStackRefresh().catch(e => {
  console.error(e);
  process.exit(1);
});
