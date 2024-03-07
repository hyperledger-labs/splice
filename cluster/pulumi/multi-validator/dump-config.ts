// Need to import this by path and not through the module, so the module is not
// initialized when we don't want it to (to avoid pulumi configs trying to being read here)
import { initDumpConfig } from '../common/src/dump-config-common';

initDumpConfig();

async function main() {
  process.env.GCP_CLUSTER_BASENAME = 'clusterbase';

  const installNode = await import('./src/installNode');
  installNode.installNode();
}

main();
