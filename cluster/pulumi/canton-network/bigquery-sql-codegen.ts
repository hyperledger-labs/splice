// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { existsSync, unlinkSync, writeFileSync } from 'fs';

import { allScanFunctions } from './src/bigQuery_functions';

if (process.argv.length != 6) {
  console.error(
    'Usage: npm run bigquery-sql-codegen <project> <functions-dataset-name> <scan-dataset-name> <output-file>'
  );
  process.exit(1);
}

const project = process.argv[2];
const functionsDatasetName = process.argv[3];
const scanDatasetName = process.argv[4];
const out = process.argv[5];

if (existsSync(out)) {
  unlinkSync(out);
}
// Note that we're currently code-generating only the scan functions, not the dashboards ones, as the latter are not
// tested in integration tests.
allScanFunctions.forEach(f =>
  writeFileSync(out, f.toSql(project, functionsDatasetName, scanDatasetName, ''), { flag: 'a' })
);
