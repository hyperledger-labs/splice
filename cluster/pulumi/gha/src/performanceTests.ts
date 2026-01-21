// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { CloudPostgres, ExactNamespace } from '@lfdecentralizedtrust/splice-pulumi-common';

export function createCloudSQLInstanceForPerformanceTests(
  ghaNamespace: ExactNamespace
): CloudPostgres {
  return new CloudPostgres(
    ghaNamespace,
    'performance-test-db',
    'performance-test-db',
    'performance-test-db-secret',
    {
      enabled: true,
      maintenanceWindow: { day: 2, hour: 8 },
      protected: false,
      tier: 'db-custom-2-7680', // same as devnet & testnet as of Jan 2026
      enterprisePlus: false,
    },
    true,
    {
      disableProtection: true,
      disableBackups: true,
      logicalDecoding: false,
    }
  );
}
