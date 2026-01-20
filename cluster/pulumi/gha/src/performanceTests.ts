import { CloudPostgres, ExactNamespace } from '@lfdecentralizedtrust/splice-pulumi-common';

export function installPerformanceTestsServices(ghaNamespace: ExactNamespace): void {
  createCloudSQLInstanceForPerformanceTests(ghaNamespace);
}

function createCloudSQLInstanceForPerformanceTests(ghaNamespace: ExactNamespace): void {
  new CloudPostgres(
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
    false, // that means that it will start stopped
    {
      disableProtection: true,
      disableBackups: true,
      logicalDecoding: false,
    }
  );
}
