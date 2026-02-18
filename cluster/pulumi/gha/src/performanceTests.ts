// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as gcp from '@pulumi/gcp';
import * as pulumi from '@pulumi/pulumi';
import * as random from '@pulumi/random';
import {
  CloudSqlConfigSchema,
  CLUSTER_BASENAME,
  config,
  ExactNamespace,
  GCP_REGION,
  GCP_ZONE,
  installPostgresPasswordSecret,
} from '@lfdecentralizedtrust/splice-pulumi-common';

export interface PerformanceTestDb {
  db: gcp.sql.Database;
  address: pulumi.Output<string>;
  secretName: pulumi.Output<string>;
}

// trimmed down version of common/postgres.ts that will not blow up due to imports of global variables
export function createCloudSQLInstanceForPerformanceTests(
  ghaNamespace: ExactNamespace
): PerformanceTestDb {
  const mainnetLikeConfig = CloudSqlConfigSchema.parse({
    enabled: true,
    protected: false,
    tier: 'db-custom-4-9728',
    enterprisePlus: false,
  });
  const zone = GCP_ZONE || config.requireEnv('DB_CLOUDSDK_COMPUTE_ZONE');
  const instance = new gcp.sql.DatabaseInstance('performance-tests-db', {
    databaseVersion: 'POSTGRES_14',
    deletionProtection: false,
    region: GCP_REGION,
    settings: {
      deletionProtectionEnabled: false,
      activationPolicy: 'ALWAYS', // no other option seems to work
      databaseFlags: Object.keys(mainnetLikeConfig.flags).map(name => {
        return { name, value: mainnetLikeConfig.flags[name] };
      }),
      backupConfiguration: {
        enabled: false,
        pointInTimeRecoveryEnabled: false,
      },
      insightsConfig: {
        // presumably useful in case of sudden slow queries
        queryInsightsEnabled: true,
      },
      tier: 'db-custom-4-9728', // tier used on Mainnet as of 03.02.2026
      edition: 'ENTERPRISE', // cn-apps don't use ENTERPRISE_PLUS as of 03.02.2026
      ipConfiguration: {
        ipv4Enabled: false,
        privateNetwork: pulumi.interpolate`projects/${gcp.organizations.getProjectOutput({}).name}/global/networks/default`,
        enablePrivatePathForGoogleCloudServices: true,
      },
      userLabels: {
        cluster: CLUSTER_BASENAME,
      },
      locationPreference: {
        zone,
      },
      maintenanceWindow: { day: 7, hour: 12 }, // sunday at 12 UTC while there are no tests running
    },
  });

  const logicalDb = new gcp.sql.Database(
    `performance-tests-db-cantonnet`,
    {
      instance: instance.name,
      name: 'cantonnet',
    },
    {
      parent: instance,
      deletedWith: instance,
      protect: false,
    }
  );

  const password = generatePassword('performance-tests-db-pw', {
    parent: logicalDb,
    protect: false,
  }).result;
  const passwordSecret = installPostgresPasswordSecret(
    ghaNamespace,
    password,
    'performance-tests-db-secret'
  );

  new gcp.sql.User(
    `performance-tests-db-user`,
    {
      instance: instance.name,
      name: 'cnadmin',
      password: password,
    },
    {
      parent: logicalDb,
      deletedWith: logicalDb,
      dependsOn: [logicalDb],
      protect: false,
    }
  );

  return {
    db: logicalDb,
    address: instance.privateIpAddress,
    secretName: passwordSecret.metadata.name,
  };
}

function generatePassword(
  name: string,
  opts?: pulumi.ResourceOptions & Required<Pick<pulumi.ResourceOptions, 'parent'>>
): random.RandomPassword {
  return new random.RandomPassword(
    name,
    {
      length: 16,
      overrideSpecial: '_%@',
      special: true,
    },
    opts
  );
}
