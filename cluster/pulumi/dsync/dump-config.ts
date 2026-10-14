// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  initDumpConfig,
  SecretsFixtureMap,
  svRunbookAuth0Config,
} from '../common/src/dump-config-common';

async function main() {
  await initDumpConfig();
  //TODO(splice#7780): expected
}

main().catch(e => {
  console.error(e.stack ?? e.message ?? e);
  process.exit(1);
});
