// Need to import this by path and not through the module, so the module is not
// initialized when we don't want it to (to avoid pulumi configs trying to being read here)
import { initDumpConfig } from '../common/src/dump-config-common';

async function main() {
  await initDumpConfig();
  const gcpproject = await import('./src/gcpProject');
  new gcpproject.GcpProject('da-cn-example-project-id', {
    gcpProjectId: 'da-cn-example-project-id',
  });
}

main();
