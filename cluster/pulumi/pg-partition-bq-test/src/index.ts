// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
//
// Throwaway CloudSQL -> Datastream -> BigQuery pipeline with a partitioned
// source table, to validate the #6312 cutover against the stag-prod Datastream
// configuration. See ../README.md for the test procedure.
//
// The network path, connection profiles and stream mirror
// common-sv/src/bigQuery.ts; the publication and replication slot are created
// by the real canton-network/bigquery-cloudsql.sh.
import * as command from '@pulumi/command';
import * as gcp from '@pulumi/gcp';
import * as pulumi from '@pulumi/pulumi';
import * as ip from 'ip';
import { config } from '@canton-network/splice-pulumi-common/src/config';
import { spliceConfig } from '@canton-network/splice-pulumi-common/src/config/config';
import {
  defaultUserName,
  generatePassword,
  getCloudSdkZone,
  privateNetworkId,
} from '@canton-network/splice-pulumi-common/src/postgres';
import {
  CLUSTER_BASENAME,
  GCP_REGION,
  commandScriptPath,
} from '@canton-network/splice-pulumi-common/src/utils';

// scratchnets share a GCP project, so every globally-named resource carries the cluster
const prefix = `${CLUSTER_BASENAME}-pgpart`;
const dbPort = 5432;
const replicatorUserName = 'bqdatastream';
const databaseName = 'partition_test';
const schemaName = databaseName;
const publicationName = 'pgpart_stag_prod_pub';
const replicationSlotName = 'pgpart_stag_prod_r_slot';
const fakeFlywayMigration = 'V001__pgpart_init.sql';
const tablesToReplicate = ['partitioned_events', 'cutover_events'];
// marks everything this test creates, for finding leftovers; see README.md
const testingTag = 'pgpart-pr7737';
const labels = { cluster: CLUSTER_BASENAME, testing: testingTag };

const project = gcp.organizations.getProjectOutput({});
const zone = getCloudSdkZone();

// canton-network's scan Datastream uses the /29 right after GCP_MASTER_IPV4_CIDR;
// take the /29 after that one.
function pickDatastreamPeeringCidr(): string {
  const baseCidr = config.requireEnv('GCP_MASTER_IPV4_CIDR');
  const baseSubnet = ip.cidrSubnet(baseCidr);
  if (baseSubnet.subnetMaskLength !== 28) {
    throw new Error(`Expected a /28 CIDR, but got ${baseCidr}`);
  }
  return ip.fromLong(ip.toLong(baseSubnet.networkAddress) + baseSubnet.length + 8) + '/29';
}

// ============================================================================
// CloudSQL
// ============================================================================

const pgsqlInstance = new gcp.sql.DatabaseInstance(`${prefix}-db`, {
  databaseVersion: 'POSTGRES_18',
  deletionProtection: false,
  region: GCP_REGION,
  settings: {
    deletionProtectionEnabled: false,
    activationPolicy: 'ALWAYS',
    databaseFlags: [
      ...Object.entries(spliceConfig.pulumiProjectConfig.cloudSql.flags).map(([name, value]) => ({
        name,
        value,
      })),
      { name: 'cloudsql.logical_decoding', value: 'on' },
    ],
    backupConfiguration: {
      enabled: false,
      pointInTimeRecoveryEnabled: false,
    },
    tier: 'db-custom-1-3840',
    edition: 'ENTERPRISE',
    ipConfiguration: {
      ipv4Enabled: false,
      privateNetwork: privateNetworkId,
      enablePrivatePathForGoogleCloudServices: true,
    },
    userLabels: labels,
    locationPreference: { zone },
  },
});

const database = new gcp.sql.Database(
  `${prefix}-db-${databaseName}`,
  { instance: pgsqlInstance.name, name: databaseName },
  { parent: pgsqlInstance, deletedWith: pgsqlInstance }
);

const adminPassword = generatePassword(`${prefix}-${defaultUserName}-passwd`, {
  parent: pgsqlInstance,
}).result;
const adminUser = new gcp.sql.User(
  `${prefix}-user-${defaultUserName}`,
  { instance: pgsqlInstance.name, name: defaultUserName, password: adminPassword },
  { parent: pgsqlInstance, deletedWith: pgsqlInstance, dependsOn: [database] }
);

const replicatorPassword = generatePassword(`${prefix}-${replicatorUserName}-passwd`, {
  parent: pgsqlInstance,
}).result;
const replicatorUser = new gcp.sql.User(
  `${prefix}-user-${replicatorUserName}`,
  { instance: pgsqlInstance.name, name: replicatorUserName, password: replicatorPassword },
  { parent: pgsqlInstance, deletedWith: pgsqlInstance, dependsOn: [database] }
);

// ============================================================================
// Schema, tables and the publication/replication slot
// ============================================================================

// Runs as cnadmin, which therefore owns the tables, as CREATE PUBLICATION requires.
// The flyway_schema_history row satisfies bigquery-cloudsql.sh's migration gate.
const initSql = `
CREATE SCHEMA IF NOT EXISTS ${schemaName};
SET search_path TO ${schemaName};

CREATE TABLE IF NOT EXISTS flyway_schema_history (
  script text PRIMARY KEY,
  success boolean NOT NULL
);
INSERT INTO flyway_schema_history VALUES ('${fakeFlywayMigration}', true)
  ON CONFLICT DO NOTHING;

-- Shaped like the #6312 partitioned parents.
CREATE TABLE IF NOT EXISTS partitioned_events (
  row_id bigserial,
  record_time bigint NOT NULL,
  payload text,
  PRIMARY KEY (record_time, row_id)
) PARTITION BY RANGE (record_time);
-- static import-update partition, up to CantonTimestamp.MinValue + 1us
CREATE TABLE IF NOT EXISTS partitioned_events_import
  PARTITION OF partitioned_events FOR VALUES FROM (MINVALUE) TO (-62135596799999999);

-- Shaped like today's unpartitioned update_history_* tables; the README
-- renames it to cutover_events_legacy and swaps in a partitioned parent.
CREATE TABLE IF NOT EXISTS cutover_events (
  row_id bigserial PRIMARY KEY,
  record_time bigint NOT NULL,
  payload text
);
`;

const bucketLabels = Object.entries(labels)
  .map(([key, value]) => `${key}=${value}`)
  .join(',');
const initDb = new command.local.Command(
  `${prefix}-init-db`,
  {
    create: pulumi.interpolate`'${commandScriptPath('cluster/pulumi/pg-partition-bq-test/import-sql.sh')}' \
      --project="${project.projectId}" \
      --compute-region="${GCP_REGION}" \
      --service-account-email="${pgsqlInstance.serviceAccountEmailAddress}" \
      --postgres-instance-name="${pgsqlInstance.name}" \
      --database="${databaseName}" \
      --user="${defaultUserName}" \
      --bucket-labels="${bucketLabels}"`,
    stdin: initSql,
  },
  { dependsOn: [database, adminUser] }
);

function pubRepSlotCommand(action: string): pulumi.Output<string> {
  const args = [
    pulumi.interpolate`--private-network-project="${project.projectId}"`,
    pulumi.interpolate`--compute-region="${GCP_REGION}"`,
    pulumi.interpolate`--service-account-email="${pgsqlInstance.serviceAccountEmailAddress}"`,
    pulumi.interpolate`--schema-name="${schemaName}"`,
    pulumi.interpolate`--tables-to-replicate-joined="${tablesToReplicate.join(', ')}"`,
    pulumi.interpolate`--postgres-user-name="${defaultUserName}"`,
    pulumi.interpolate`--publication-name="${publicationName}"`,
    pulumi.interpolate`--replication-slot-name="${replicationSlotName}"`,
    pulumi.interpolate`--replicator-user-name="${replicatorUserName}"`,
    pulumi.interpolate`--postgres-instance-name="${pgsqlInstance.name}"`,
    pulumi.interpolate`--scan-app-database-name="${databaseName}"`,
    pulumi.interpolate`--flyway-migration-to-wait-for="${fakeFlywayMigration}"`,
  ];
  const scriptPath = commandScriptPath('cluster/pulumi/canton-network/bigquery-cloudsql.sh');
  return pulumi
    .all(args)
    .apply(as => `'${scriptPath}' ${action} \\\n      ${as.join(' \\\n      ')}`);
}

const pubRepSlot = new command.local.Command(
  `${prefix}-pub-replicate-slot`,
  {
    create: pubRepSlotCommand('create-pub-rep-slot'),
    delete: pubRepSlotCommand('delete-pub-rep-slot'),
  },
  { dependsOn: [initDb, adminUser, replicatorUser], deleteBeforeReplace: true }
);

// ============================================================================
// Network path: Datastream VPC peering -> NAT VM -> CloudSQL private IP
// ============================================================================

// from https://cloud.google.com/datastream/docs/private-connectivity#set-up-reverse-proxy
const natStartupScript = pulumi.interpolate`#! /bin/bash
export DB_ADDR=${pgsqlInstance.privateIpAddress}
export DB_PORT=${dbPort}
echo 1 > /proc/sys/net/ipv4/ip_forward
md_url_prefix="http://169.254.169.254/computeMetadata/v1/instance"
vm_nic_ip="$(curl -H "Metadata-Flavor: Google" $md_url_prefix/network-interfaces/0/ip)"
iptables -t nat -F
iptables -t nat -A PREROUTING -p tcp --dport $DB_PORT -j DNAT --to-destination $DB_ADDR
iptables -t nat -A POSTROUTING -p tcp --dport $DB_PORT -j SNAT --to-source $vm_nic_ip
iptables-save
`;

const natVm = new gcp.compute.Instance(`${prefix}-nat-vm`, {
  machineType: 'e2-micro',
  zone,
  bootDisk: { initializeParams: { image: 'debian-cloud/debian-12', labels } },
  networkInterfaces: [{ network: 'default', accessConfigs: [{}] }],
  // network tags are plain strings; no firewall rule targets this one
  tags: [testingTag],
  metadata: {
    'enable-osconfig': 'TRUE',
    'enable-oslogin': 'true',
    'startup-script': natStartupScript,
  },
  labels,
});

const privateConnection = new gcp.datastream.PrivateConnection(
  `${prefix}-datastream-vpc`,
  {
    privateConnectionId: `${prefix}-datastream-vpc`,
    displayName: `${prefix}-datastream-vpc`,
    location: GCP_REGION,
    vpcPeeringConfig: { subnet: pickDatastreamPeeringCidr(), vpc: privateNetworkId },
    labels,
  },
  { deleteBeforeReplace: true }
);

new gcp.compute.Firewall(`${prefix}-datastream-to-nat`, {
  name: `${prefix}-datastream-to-nat`,
  // firewall rules take no labels
  description: testingTag,
  direction: 'INGRESS',
  priority: 42,
  network: 'default',
  allows: [{ protocol: 'tcp', ports: [dbPort.toString()] }],
  sourceRanges: privateConnection.vpcPeeringConfig.apply(c => (c ? [c.subnet] : [])),
  destinationRanges: [natVm.networkInterfaces[0].networkIp],
});

// ============================================================================
// Datastream and BigQuery, shaped like the stag-prod stream
// ============================================================================

const sourceProfile = new gcp.datastream.ConnectionProfile(
  `${prefix}-pg-cxn`,
  {
    connectionProfileId: `${prefix}-pg-cxn`,
    displayName: `${prefix}-pg-cxn`,
    location: GCP_REGION,
    postgresqlProfile: {
      hostname: natVm.networkInterfaces[0].networkIp,
      port: dbPort,
      username: replicatorUserName,
      password: replicatorPassword,
      database: databaseName,
    },
    privateConnectivity: { privateConnection: privateConnection.name },
    labels,
  },
  { dependsOn: [natVm, privateConnection, replicatorUser, pubRepSlot] }
);

const datasetId = `pgpart_${CLUSTER_BASENAME.replace(/-/g, '_')}`;
const dataset = new gcp.bigquery.Dataset(datasetId, {
  datasetId,
  friendlyName: `${datasetId} Dataset`,
  location: GCP_REGION,
  deleteContentsOnDestroy: true,
  labels,
});

const destinationProfile = new gcp.datastream.ConnectionProfile(
  `${prefix}-bq-cxn`,
  {
    connectionProfileId: `${prefix}-bq-cxn`,
    displayName: `${prefix}-bq-cxn`,
    location: GCP_REGION,
    bigqueryProfile: {},
    labels,
  },
  { dependsOn: [dataset, privateConnection] }
);

const streamId = `${prefix}-stag-prod`;
const stream = new gcp.datastream.Stream(
  streamId,
  {
    location: GCP_REGION,
    streamId,
    displayName: streamId,
    desiredState: 'RUNNING',
    sourceConfig: {
      postgresqlSourceConfig: {
        // only the partitioned parents, never their partitions
        includeObjects: {
          postgresqlSchemas: [
            {
              schema: schemaName,
              postgresqlTables: tablesToReplicate.map(table => ({ table })),
            },
          ],
        },
        publication: publicationName,
        replicationSlot: replicationSlotName,
      },
      sourceConnectionProfile: sourceProfile.name,
    },
    destinationConfig: {
      bigqueryDestinationConfig: {
        singleTargetDataset: {
          datasetId: pulumi.interpolate`projects/${dataset.project}/datasets/${dataset.datasetId}`,
        },
        dataFreshness: '0s',
        appendOnly: {},
      },
      destinationConnectionProfile: destinationProfile.name,
    },
    backfillNone: {},
    ruleSets: tablesToReplicate.map(table => ({
      objectFilter: {
        sourceObjectIdentifier: { postgresqlIdentifier: { schema: schemaName, table } },
      },
      customizationRules: [
        {
          bigqueryPartitioning: {
            ingestionTimePartition: {
              partitioningTimeGranularity: 'PARTITIONING_TIME_GRANULARITY_HOUR',
            },
          },
        },
      ],
    })),
    labels,
  },
  { dependsOn: [sourceProfile, destinationProfile, dataset, pubRepSlot] }
);

export const instanceName = pgsqlInstance.name;
export const privateIp = pgsqlInstance.privateIpAddress;
export const natVmName = natVm.name;
export const natVmZone = natVm.zone;
export const postgresDatabase = databaseName;
export const postgresUser = defaultUserName;
export const cnadminPassword = pulumi.secret(adminPassword);
export const bigQueryDataset = pulumi.interpolate`${dataset.project}.${dataset.datasetId}`;
export const datastreamStreamId = stream.streamId;
export const datastreamLocation = GCP_REGION;
export const publication = publicationName;
