// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { existsSync, unlinkSync, writeFileSync } from 'fs';

import {
  allDashboardFunctions,
  allScanFunctions,
  computedDataTable,
} from './src/bigQuery_functions';

if (process.argv.length != 7) {
  console.error(
    'Usage: npm run bigquery-sql-codegen <project> <functions-dataset-name> <scan-dataset-name> <dashboards-dataset-name> <output-file>'
  );
  process.exit(1);
}

const project = process.argv[2];
const functionsDatasetName = process.argv[3];
const scanDatasetName = process.argv[4];
const dashboardsDatasetName = process.argv[5];
const out = process.argv[6];

if (existsSync(out)) {
  unlinkSync(out);
}
allScanFunctions.forEach(f =>
  writeFileSync(
    out,
    f.toSql(
      project,
      functionsDatasetName,
      functionsDatasetName,
      scanDatasetName,
      dashboardsDatasetName
    ),
    { flag: 'a' }
  )
);

writeFileSync(out, computedDataTable.toSql(dashboardsDatasetName), { flag: 'a' });

allDashboardFunctions.forEach(f =>
  writeFileSync(
    out,
    f.toSql(
      project,
      dashboardsDatasetName,
      functionsDatasetName,
      scanDatasetName,
      dashboardsDatasetName
    ),
    { flag: 'a' }
  )
);
