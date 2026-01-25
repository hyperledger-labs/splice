// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
// Need to import this by path and not through the module, so the module is not
// initialized when we don't want it to (to avoid pulumi configs trying to being read here)
import { initDumpConfig } from '../common/src/dump-config-common';

async function main() {
  await initDumpConfig();

  process.env.SLACK_ACCESS_TOKEN = 's3cr3t';
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  const infra: typeof import('./src/index') = await import('./src/index');
}

// eslint-disable-next-line @typescript-eslint/no-floating-promises
main();
