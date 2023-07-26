// Need to import this by path and not through the module, so the module is not
// initialized when we don't want it to (to avoid pulumi configs trying to being read here)
import { initDumpConfig } from '../common/src/dump-config-common';

initDumpConfig();

async function main() {
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  const observability: typeof import('./index') = await import('./index');
}

main();
