import * as pulumi from '@pulumi/pulumi';

// Need to import this by path and not through the module, so the module is not
// initialized when we don't want it to (to avoid pulumi configs trying to being read here)
import { initDumpConfig } from '../common/src/dump-config-common';

pulumi.runtime.setConfig('test-project:clusterBasenames', '["mock_cluster"]');

initDumpConfig();

async function main() {
  process.env.SLACK_ACCESS_TOKEN = 's3cr3t';
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  const infra: typeof import('./src/index') = await import('./src/index');
}

// eslint-disable-next-line @typescript-eslint/no-floating-promises
main();
