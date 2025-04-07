// Need to import this by path and not through the module, so the module is not
// initialized when we don't want it to (to avoid pulumi configs trying to being read here)
import { initDumpConfig } from '../common/src/dump-config-common';
import { GcpProject } from './src/gcp-project';

async function main() {
  await initDumpConfig();
  await import('./src/gcp-project');
  new GcpProject('project-id');
}

// eslint-disable-next-line @typescript-eslint/no-floating-promises
main().catch(e => {
  console.error(e);
  process.exit(1);
});
