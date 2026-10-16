// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
// Need to import this by path and not through the module, so the module is not
// initialized when we don't want it to (to avoid pulumi configs trying to being read here)
import { multiValidatorConfig } from '@canton-network/splice-pulumi-common/src/config/multiValidatorConfig';

import { initDumpConfig } from '../common/src/dump-config-common';

async function main() {
  await initDumpConfig();
  const installNode = await import('./src/installNode');
  if (multiValidatorConfig !== undefined && multiValidatorConfig.instanceCount > 0) {
    await installNode.installNode(multiValidatorConfig);
  }
}

main().catch(e => {
  console.error(e.stack ?? e.message ?? e);
  process.exit(1);
});
