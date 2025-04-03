// Need to import this by path and not through the module, so the module is not
// initialized when we don't want it to (to avoid pulumi configs trying to being read here)
import { initDumpConfig } from '../common/src/dump-config-common';
import { installInternalWhitelists } from './src/whitelists';

async function main() {
  await initDumpConfig();
  await import('./src/whitelists');
  installInternalWhitelists();
}

// eslint-disable-next-line @typescript-eslint/no-floating-promises
main().catch(e => {
  console.error(e);
  process.exit(1);
});
