// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
// Need to import this by path and not through the module, so the module is not
// initialized when we don't want it to (to avoid pulumi configs trying to being read here)
import { initDumpConfig } from '../common/src/dump-config-common';
import { GcpProject } from './src/gcp-project';
import { SPLICE_ROOT, config } from '../common';

async function main() {
  await initDumpConfig();

  // Set configs dir to mocks if it doesn't exist
  const configsDir = config.optionalEnv('GCP_PROJECT_CONFIGS_DIR') || `${SPLICE_ROOT}/cluster/configs/gcp-project-mock`;

  await import('./src/gcp-project');
  new GcpProject('project-id', configsDir);
}

// eslint-disable-next-line @typescript-eslint/no-floating-promises
main().catch(e => {
  console.error(e);
  process.exit(1);
});
