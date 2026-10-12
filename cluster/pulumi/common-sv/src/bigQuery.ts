// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as command from '@pulumi/command';
import * as gcp from '@pulumi/gcp';
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import * as fs from 'fs';
import * as ip from 'ip';
import * as path from 'path';
import { clusterProdLike, config } from '@canton-network/splice-pulumi-common/src/config';
import { spliceConfig } from '@canton-network/splice-pulumi-common/src/config/config';
import type { InstalledHelmChart } from '@canton-network/splice-pulumi-common/src/helm';
import {
  defaultUserName,
  generatePassword,
  getCloudSdkZone,
  privateNetworkId,
} from '@canton-network/splice-pulumi-common/src/postgres';
import { installPostgresPasswordSecret } from '@canton-network/splice-pulumi-common/src/secrets';
import {
  ExactNamespace,
  CLUSTER_BASENAME,
  GCP_PROJECT,
  GCP_REGION,
  commandScriptPath,
} from '@canton-network/splice-pulumi-common/src/utils';

import { ScanBigQueryConfig } from './singleSvConfig';

// ============================================================================
// PIPELINE CONFIGURATION & TYPES
// ============================================================================

const THREE_DAYS_MS = 3 * 24 * 60 * 60 * 1000;

interface PostgresPassword {
  contents: pulumi.Output<string>;
  secret: k8s.core.v1.Secret;
}

const dbPort = 5432;
const replicatorUserName = 'bqdatastream';

// Remove legacy datastream configuration once migration to stag-prod pipeline is verified.
// issue: https://github.com/canton-network/splice/issues/6656
// TODO (#6656) Remove legacy datastream configuration once migration to stag-prod pipeline is verified

const replicationSlotName = 'update_history_datastream_r_slot';
const publicationName = 'update_history_datastream_pub';

// Stream 2 (Stag-Prod) CDC Replication Configuration
const replicationSlotNameStagProd = 'update_history_datastream_stag_prod_r_slot';
const publicationNameStagProd = 'update_history_datastream_stag_prod_pub';

const flywayMigrationToWaitFor = 'V068__app_activity_record_meta.sql';

// ============================================================================
// SINGLE SOURCE OF TRUTH: REPLICATED TABLE CONFIGURATION
// ============================================================================
// what tables from Scan to replicate to BigQuery
interface ReplicatedTableConfig {
  primaryKey: string;
  datePartitionColumn: string;
  timeType: 'micros' | 'datastream_metadata';
}

const replicatedTables: Record<string, ReplicatedTableConfig> = {
  update_history_creates: {
    primaryKey: 'row_id',
    datePartitionColumn: 'record_time',
    timeType: 'micros',
  },
  update_history_exercises: {
    primaryKey: 'row_id',
    datePartitionColumn: 'record_time',
    timeType: 'micros',
  },
  scan_verdict_store: {
    primaryKey: 'row_id',
    datePartitionColumn: 'record_time',
    timeType: 'micros',
  },
  scan_verdict_transaction_view_store: {
    primaryKey: 'verdict_row_id, view_id',
    datePartitionColumn: 'source_timestamp',
    timeType: 'datastream_metadata',
  },
  app_activity_record_store: {
    primaryKey: 'verdict_row_id',
    datePartitionColumn: 'source_timestamp',
    timeType: 'datastream_metadata',
  },
};

const tablesToReplicate = Object.keys(replicatedTables);

// ============================================================================
// HELPER FUNCTIONS
// ============================================================================

function cloudsdkComputeRegion() {
  return config.requireEnv('CLOUDSDK_COMPUTE_REGION');
}

function pickDatastreamPeeringCidr(): string {
  const baseCidr = config.requireEnv('GCP_MASTER_IPV4_CIDR');
  const baseSubnet = ip.cidrSubnet(baseCidr);
  // assert GCP_MASTER_IPV4_CIDR is a /28 CIDR
  if (baseSubnet.subnetMaskLength !== 28) {
    throw new Error(`Expected a /28 CIDR, but got ${baseCidr}`);
  }

  return ip.fromLong(ip.toLong(baseSubnet.networkAddress) + baseSubnet.length) + '/29';
}

function installNatVm(
  namespace: ExactNamespace,
  zone: string,
  databaseInstance: gcp.sql.DatabaseInstance
): gcp.compute.Instance {
  const vmName = `${namespace.logicalName}-nat-vm`;
  // from https://cloud.google.com/datastream/docs/private-connectivity#set-up-reverse-proxy
  const startupScript = pulumi.interpolate`#! /bin/bash

export DB_ADDR=${databaseInstance.privateIpAddress}
export DB_PORT=${dbPort}

# Enable the VM to receive packets whose destinations do
# not match any running process local to the VM
echo 1 > /proc/sys/net/ipv4/ip_forward

# Ask the Metadata server for the IP address of the VM nic0
# network interface:
md_url_prefix="http://169.254.169.254/computeMetadata/v1/instance"
vm_nic_ip="$(curl -H "Metadata-Flavor: Google" $md_url_prefix/network-interfaces/0/ip)"

# Clear any existing iptables NAT table entries (all chains):
iptables -t nat -F

# Create a NAT table entry in the prerouting chain, matching
# any packets with destination database port, changing the destination
# IP address of the packet to the SQL instance IP address:
iptables -t nat -A PREROUTING \\
     -p tcp --dport $DB_PORT \\
     -j DNAT \\
     --to-destination $DB_ADDR

# Create a NAT table entry in the postrouting chain, matching
# any packets with destination database port, changing the source IP
# address of the packet to the NAT VM's primary internal IPv4 address:
iptables -t nat -A POSTROUTING \\
     -p tcp --dport $DB_PORT \\
     -j SNAT \\
     --to-source $vm_nic_ip

# Save iptables configuration:
iptables-save
`;

  return new gcp.compute.Instance(vmName, {
    machineType: 'e2-micro',
    zone,
    bootDisk: {
      initializeParams: {
        image: 'debian-cloud/debian-12',
      },
    },
    networkInterfaces: [
      {
        network: 'default',
        accessConfigs: [{}], // ephemeral external IP
      },
    ],
    metadata: {
      'enable-osconfig': 'TRUE',
      'enable-oslogin': 'true',
      'startup-script': startupScript,
    },
    labels: {
      cluster: CLUSTER_BASENAME,
    },
  });
}

// ============================================================================
// DATASTREAM PIPELINE DEFINITIONS
// ============================================================================

function installDatastream(
  namespace: ExactNamespace,
  databaseInstance: gcp.sql.DatabaseInstance,
  source: gcp.datastream.ConnectionProfile,
  destination: gcp.datastream.ConnectionProfile,
  bigQueryDataset: gcp.bigquery.Dataset,
  pubRepSlots: pulumi.Resource,
  desiredState: 'RUNNING' | 'PAUSED'
): gcp.datastream.Stream {
  const streamName = `${namespace.logicalName}-scan-update-history`;
  const schemaName = scanAppDatabaseName(namespace);
  return new gcp.datastream.Stream(
    streamName,
    {
      location: cloudsdkComputeRegion(),
      streamId: streamName,
      displayName: streamName,
      desiredState: desiredState,
      sourceConfig: {
        postgresqlSourceConfig: {
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
        sourceConnectionProfile: source.name,
      },
      destinationConfig: {
        bigqueryDestinationConfig: {
          singleTargetDataset: {
            datasetId: pulumi.interpolate`projects/${bigQueryDataset.project}/datasets/${bigQueryDataset.datasetId}`,
          },
          // editing dataFreshness does not alter existing BQ tables, see its
          // docstring or https://github.com/canton-network/splice/issues/2011
          dataFreshness: clusterProdLike ? '14400s' : '0s',
        },
        destinationConnectionProfile: destination.name,
      },
      backfillAll: {},
      labels: {
        cluster: CLUSTER_BASENAME,
        datastream_id: 'legacy',
      },
    },
    { dependsOn: [databaseInstance, source, destination, bigQueryDataset, pubRepSlots] }
  );
}

function installDatastream_stag_prod(
  namespace: ExactNamespace,
  databaseInstance: gcp.sql.DatabaseInstance,
  source: gcp.datastream.ConnectionProfile,
  destination: gcp.datastream.ConnectionProfile,
  bigQueryDataset: gcp.bigquery.Dataset,
  pubRepSlots: pulumi.Resource,
  desiredState: 'RUNNING' | 'PAUSED'
): gcp.datastream.Stream {
  const streamName = `${CLUSTER_BASENAME}-${namespace.logicalName}-stag-production-datastream`;
  const schemaName = scanAppDatabaseName(namespace);
  return new gcp.datastream.Stream(
    streamName,
    {
      location: cloudsdkComputeRegion(),
      streamId: streamName,
      displayName: streamName,
      desiredState: desiredState,
      sourceConfig: {
        postgresqlSourceConfig: {
          includeObjects: {
            postgresqlSchemas: [
              {
                schema: schemaName,
                postgresqlTables: tablesToReplicate.map(table => ({ table })),
              },
            ],
          },
          publication: publicationNameStagProd,
          replicationSlot: replicationSlotNameStagProd,
        },
        sourceConnectionProfile: source.name,
      },
      destinationConfig: {
        bigqueryDestinationConfig: {
          singleTargetDataset: {
            datasetId: pulumi.interpolate`projects/${bigQueryDataset.project}/datasets/${bigQueryDataset.datasetId}`,
          },
          dataFreshness: '0s',
          appendOnly: {},
        },
        destinationConnectionProfile: destination.name,
      },
      backfillNone: {}, // Addressing issue #6919 - partition overflow problem with backfillAll, so using backfillNone for stag-prod datastream
      ruleSets: tablesToReplicate.map(tableName => ({
        objectFilter: {
          sourceObjectIdentifier: {
            postgresqlIdentifier: {
              schema: schemaName,
              table: tableName,
            },
          },
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
      labels: {
        cluster: CLUSTER_BASENAME,
        datastream_id: 'stag_prod',
      },
    },
    {
      dependsOn: [databaseInstance, source, destination, bigQueryDataset, pubRepSlots],
    }
  );
}

// ============================================================================
// BIGQUERY DATASET CREATION
// ============================================================================

function installBigqueryDataset(scanBigQuery: ScanBigQueryConfig): gcp.bigquery.Dataset {
  return new gcp.bigquery.Dataset(scanBigQuery.dataset, {
    datasetId: scanBigQuery.dataset,
    friendlyName: `${scanBigQuery.dataset} Dataset`,
    location: cloudsdkComputeRegion(),
    deleteContentsOnDestroy: true, //retaining old value
    // TODO (DACH-NY/canton-network-internal#343) reduce time travel window from 7-day default to 2 days if
    // it makes a cost difference
    labels: {
      cluster: CLUSTER_BASENAME,
      datastream_id: 'legacy',
    },
  });
}
/* TODO (DACH-NY/canton-network-internal#341) remove this comment when enabled on all relevant clusters
If you see an error like this
  gcp:datastream:ConnectionProfile (sv-4-scan-bq-cxn):
    error: 1 error occurred:
      * Error creating ConnectionProfile: googleapi: Error 403: Datastream API has not been used in project da-cn-scratchnet before or it is disabled. Enable it by visiting https://console.developers.google.com/apis/api/datastream.googleapis.com/overview?project=da-cn-scratchnet then retry. If you enabled this API recently, wait a few minutes for the action to propagate to our systems and retry.

or the same for

  gcp:datastream:PrivateConnection (sv-4-scan-update-history-datastream-vpc)

you have to manually enable the API as described for that cluster.
- done for da-cn-scratchnet
- done for da-cn-ci-2
 */

function installBigqueryStagingDataset(scanBigQuery: ScanBigQueryConfig): gcp.bigquery.Dataset {
  return new gcp.bigquery.Dataset(`${scanBigQuery.dataset}-staging`, {
    datasetId: `${scanBigQuery.dataset}_staging`,
    friendlyName: `${scanBigQuery.dataset} Staging Dataset`,
    location: cloudsdkComputeRegion(),
    deleteContentsOnDestroy: true,
    // ISSUE#6814: Do not rely on ingestion timestamps for retention in staging.
    // GCP calculates expiration from the table creation date, which will delete
    // staging tables at the 3-day mark even if production sync is incomplete.
    labels: {
      cluster: CLUSTER_BASENAME,
      datastream_id: 'stag_prod',
    },
  });
}

function installBigqueryProdDataset(scanBigQuery: ScanBigQueryConfig): gcp.bigquery.Dataset {
  return new gcp.bigquery.Dataset(`${scanBigQuery.dataset}-prod`, {
    datasetId: `${scanBigQuery.dataset}_prod`,
    friendlyName: `${scanBigQuery.dataset} Production Dataset`,
    location: cloudsdkComputeRegion(),
    deleteContentsOnDestroy: false,
    labels: {
      cluster: CLUSTER_BASENAME,
    },
  });
}
// ============================================================================
// IAM PERMISSIONS for SCHEDULED QUERIES
// ============================================================================
interface ScheduledQueryContext {
  projectId: pulumi.Output<string>;
  transferServiceAgentPermission: gcp.projects.IAMMember;
}

function installBqScheduledQueryContext(): ScheduledQueryContext {
  const currentProject = gcp.organizations.getProjectOutput({});
  const projectId = currentProject.apply(p => {
    if (!p.projectId) {
      throw new Error('Current GCP project output is missing a projectId.');
    }
    return p.projectId;
  });

  const transferServiceAgentPermission = new gcp.projects.IAMMember('bq-transfer-token-creator', {
    project: projectId,
    role: 'roles/iam.serviceAccountTokenCreator',
    member: currentProject.apply(
      p => `serviceAccount:service-${p.number}@gcp-sa-bigquerydatatransfer.iam.gserviceaccount.com`
    ),
  });

  return { projectId, transferServiceAgentPermission };
}

// ============================================================================
// HOURLY DEDUPLICATION & SCHEDULED QUERIES
// ============================================================================

const rawSqlTemplate = fs.readFileSync(path.join(__dirname, 'hourly_append.sql'), 'utf8');

function installHourlyScheduledQueries(
  namespace: ExactNamespace,
  stagingDataset: gcp.bigquery.Dataset,
  prodDataset: gcp.bigquery.Dataset,
  context: ScheduledQueryContext
) {
  const { projectId, transferServiceAgentPermission } = context;
  const schemaName = scanAppDatabaseName(namespace);
  Object.entries(replicatedTables).forEach(([tableName, tableConfig]) => {
    const primaryKeyExpr = tableConfig.primaryKey;
    const colName = tableConfig.datePartitionColumn;

    let recordTimestampExpr: string;
    if (tableConfig.timeType === 'micros') {
      recordTimestampExpr = `TIMESTAMP_MICROS(staging.${colName})`;
    } else if (tableConfig.timeType === 'datastream_metadata') {
      recordTimestampExpr = `TIMESTAMP_MILLIS(staging.datastream_metadata.source_timestamp)`;
    } else {
      const unreachable: never = tableConfig.timeType;
      throw new Error(`impossible time config: ${unreachable}`);
    }

    const recordDateExpr = `CAST(${recordTimestampExpr} AS DATE)`;

    const procedureBody = pulumi
      .all([projectId, prodDataset.datasetId, stagingDataset.datasetId])
      .apply(([proj, prodDs, stagingDs]) => {
        const prodTable = `\`${proj}.${prodDs}.${tableName}\``;
        const stagingTable = `\`${proj}.${stagingDs}.${schemaName}_${tableName}\``;
        const watermarksTable = `\`${proj}.${prodDs}.pipeline_watermarks\``;
        const prodInfoSchema = `\`${proj}.${prodDs}.INFORMATION_SCHEMA.TABLES\``;

        return rawSqlTemplate
          .replaceAll('{{tableName}}', tableName)
          .replaceAll('{{schemaName}}', schemaName)
          .replaceAll('{{primaryKeyExpr}}', primaryKeyExpr)
          .replaceAll('{{recordTimestampExpr}}', recordTimestampExpr)
          .replaceAll('{{recordDateExpr}}', recordDateExpr)
          .replaceAll('{{prodTable}}', prodTable)
          .replaceAll('{{stagingTable}}', stagingTable)
          .replaceAll('{{watermarksTable}}', watermarksTable)
          .replaceAll('{{prodInfoSchema}}', prodInfoSchema);
      });

    const routineId = `sp_append_${tableName}`;

    const appendRoutine = new gcp.bigquery.Routine(`${tableName}-append-routine`, {
      datasetId: prodDataset.datasetId,
      routineId: routineId,
      routineType: 'PROCEDURE',
      language: 'SQL',
      definitionBody: procedureBody,
    });

    new gcp.bigquery.DataTransferConfig(
      `${CLUSTER_BASENAME}_${tableName}-hourly-append`,
      {
        displayName: `${CLUSTER_BASENAME}_${tableName} Hourly Append Pipeline`,
        location: cloudsdkComputeRegion(),
        serviceAccountName: pulumi.interpolate`bigquery@${projectId}.iam.gserviceaccount.com`,
        dataSourceId: 'scheduled_query',
        schedule: 'every 1 hours from 00:07 to 23:07',

        params: {
          query: pulumi.interpolate`CALL \`${projectId}.${prodDataset.datasetId}.${routineId}\`();`,
        },
      },
      {
        dependsOn: [transferServiceAgentPermission, appendRoutine],
      }
    );
  });
}
// ============================================================================
// Purging data older than 7 days from the staging table
// ============================================================================
function installDailyPurgeScheduledQueries(
  namespace: ExactNamespace,
  stagingDataset: gcp.bigquery.Dataset,
  context: ScheduledQueryContext,
  retentionPeriodSeconds: number
) {
  const { projectId, transferServiceAgentPermission } = context;
  const schemaName = scanAppDatabaseName(namespace);
  const retentionDays = retentionPeriodSeconds / 86400;

  Object.entries(replicatedTables).forEach(([tableName, tableConfig]) => {
    const timeExpression =
      tableConfig.timeType === 'datastream_metadata'
        ? `TIMESTAMP_MILLIS(datastream_metadata.${tableConfig.datePartitionColumn})`
        : `TIMESTAMP_MICROS(${tableConfig.datePartitionColumn})`;

    const procedureBody = pulumi
      .all([projectId, stagingDataset.datasetId])
      .apply(([proj, stagingDs]) => {
        const stagingTable = `\`${proj}.${stagingDs}.${schemaName}_${tableName}\``;

        return `
          DELETE FROM ${stagingTable}
          WHERE ${timeExpression} < TIMESTAMP_SUB(CURRENT_TIMESTAMP(), INTERVAL ${retentionPeriodSeconds} SECOND);
        `;
      });

    const routineId = `sp_purge_old_records_${tableName}`;

    // Create the cleanup Stored Procedure
    const purgeRoutine = new gcp.bigquery.Routine(`${tableName}-purge-routine`, {
      datasetId: stagingDataset.datasetId,
      routineId: routineId,
      routineType: 'PROCEDURE',
      language: 'SQL',
      definitionBody: procedureBody,
    });

    // Schedule the Stored Procedure to run daily
    new gcp.bigquery.DataTransferConfig(
      `${CLUSTER_BASENAME}_${tableName}-daily-purge`,
      {
        displayName: `${CLUSTER_BASENAME}_${tableName} Daily Retention Purge`,
        location: cloudsdkComputeRegion(),
        serviceAccountName: pulumi.interpolate`bigquery@${projectId}.iam.gserviceaccount.com`,
        dataSourceId: 'scheduled_query',
        schedule: 'every day 05:21', // Runs daily at 05:21 AM

        params: {
          query: pulumi.interpolate`CALL \`${projectId}.${stagingDataset.datasetId}.${routineId}\`();`,
        },
      },
      {
        dependsOn: [transferServiceAgentPermission, purgeRoutine],
      }
    );
  });
}
// ============================================================================
// CONNECTION PROFILES & NETWORKING
// ============================================================================

function installBigqueryConnectionProfile(
  namespace: ExactNamespace,
  bigQuery: gcp.bigquery.Dataset,
  pcc: gcp.datastream.PrivateConnection
): gcp.datastream.ConnectionProfile {
  const profileName = `${namespace.logicalName}-scan-bq-cxn`;
  return new gcp.datastream.ConnectionProfile(
    profileName,
    {
      connectionProfileId: profileName,
      displayName: profileName,
      location: cloudsdkComputeRegion(),
      bigqueryProfile: {}, // just a sumtype marker
      labels: {
        cluster: CLUSTER_BASENAME,
      },
    },
    { dependsOn: [bigQuery, pcc] }
  );
}

function installBigqueryStagingConnectionProfile(
  namespace: ExactNamespace,
  bigQuery: gcp.bigquery.Dataset,
  pcc: gcp.datastream.PrivateConnection
): gcp.datastream.ConnectionProfile {
  const profileName = `${namespace.logicalName}-scan-bq-staging-cxn`;
  return new gcp.datastream.ConnectionProfile(
    profileName,
    {
      connectionProfileId: profileName,
      displayName: profileName,
      location: cloudsdkComputeRegion(),
      bigqueryProfile: {},
      labels: {
        cluster: CLUSTER_BASENAME,
      },
    },
    { dependsOn: [bigQuery, pcc] }
  );
}

function scanAppDatabaseName(namespace: ExactNamespace): string {
  return `scan_${namespace.logicalName.replace(/-/g, '_')}`;
}
function installPostgresConnectionProfile(
  namespace: ExactNamespace,
  databaseInstance: gcp.sql.DatabaseInstance,
  scan: InstalledHelmChart | undefined,
  natVm: gcp.compute.Instance,
  connection: gcp.datastream.PrivateConnection,
  replicatorPassword: PostgresPassword
): gcp.datastream.ConnectionProfile {
  const profileName = `${namespace.logicalName}-scan-update-history-cxn`;

  // TODO (#454) may have to await scan migration or pub/rep slots command
  return new gcp.datastream.ConnectionProfile(
    profileName,
    {
      connectionProfileId: profileName,
      displayName: profileName,
      location: cloudsdkComputeRegion(),
      postgresqlProfile: {
        hostname: natVm.networkInterfaces[0].networkIp, // NAT's private IP
        port: dbPort,
        username: replicatorUserName,
        password: replicatorPassword.contents,
        database: scanAppDatabaseName(namespace),
      },
      privateConnectivity: {
        privateConnection: connection.name,
      },
      labels: {
        cluster: CLUSTER_BASENAME,
      },
    },
    { dependsOn: [natVm, connection, databaseInstance, ...(scan !== undefined ? [scan] : [])] }
  );
}

function installPrivateConnectivityConfiguration(
  namespace: ExactNamespace
): gcp.datastream.PrivateConnection {
  const privateConnectionName = `${namespace.logicalName}-scan-update-history-datastream-vpc`;
  return new gcp.datastream.PrivateConnection(
    privateConnectionName,
    {
      privateConnectionId: privateConnectionName,
      displayName: privateConnectionName,
      location: cloudsdkComputeRegion(),
      vpcPeeringConfig: { subnet: pickDatastreamPeeringCidr(), vpc: privateNetworkId },
      labels: {
        cluster: CLUSTER_BASENAME,
      },
    },
    { deleteBeforeReplace: true }
  );
}

function installDatastreamToNatVmFirewallRule(
  namespace: ExactNamespace,
  source: gcp.datastream.PrivateConnection,
  natVm: gcp.compute.Instance
): gcp.compute.Firewall {
  const firewallRuleName = `${namespace.logicalName}-datastream-to-nat`;

  return new gcp.compute.Firewall(firewallRuleName, {
    name: firewallRuleName,
    direction: 'INGRESS',
    priority: 42,
    network: 'default',
    allows: [
      {
        protocol: 'tcp',
        ports: [dbPort.toString()],
      },
    ],
    sourceRanges: source.vpcPeeringConfig.apply(peeringConfig =>
      peeringConfig ? [peeringConfig.subnet] : []
    ),
    destinationRanges: [natVm.networkInterfaces[0].networkIp],
  });
}

// ============================================================================
// POSTGRESQL AUTHENTICATION & REPLICATION SLOT PROVISIONING
// ============================================================================
// TODO (DACH-NY/canton-network-internal#342) if we disable default egress rule, we need another firewall
// rule for Nat VM -> Postgres
function installReplicatorPassword(namespace: ExactNamespace): PostgresPassword {
  const secretName = `${namespace.logicalName}-${replicatorUserName}-passwd`;
  const password = generatePassword(`cn-apps-pg-${replicatorUserName}-passwd`, {
    aliases: [
      {
        parent: getLegacyParentUrn(namespace),
      },
    ],
    protect: spliceConfig.pulumiProjectConfig.cloudSql.protected,
  }).result;
  return {
    contents: password,
    secret: installPostgresPasswordSecret(namespace, password, secretName),
  };
}

function createPostgresReplicatorUser(
  namespace: ExactNamespace,
  databaseInstance: gcp.sql.DatabaseInstance,
  password: PostgresPassword
): gcp.sql.User {
  const name = `${namespace.logicalName}-user-${replicatorUserName}`;
  return new gcp.sql.User(
    name,
    {
      instance: databaseInstance.name,
      name: replicatorUserName,
      password: password.contents,
    },
    {
      aliases: [
        {
          parent: getLegacyParentUrn(namespace),
        },
      ],
      protect: spliceConfig.pulumiProjectConfig.cloudSql.protected,
      dependsOn: [password.secret],
    }
  );
}

/*
For the SQL below to apply, the user/operator applying the pulumi
needs the 'Cloud SQL Editor' IAM role in the relevant GCP project
 */

function createPublicationAndReplicationSlots(
  namespace: ExactNamespace,
  databaseInstance: gcp.sql.DatabaseInstance,
  replicatorUser: gcp.sql.User,
  scan: InstalledHelmChart | undefined,
  enableLegacy: boolean,
  enableStagProd: boolean
): {
  slot1?: command.local.Command;
  slot2?: command.local.Command;
} {
  // ---------------------------------------------------------------------------
  // 1. Shared Environment & Project Setup
  // ---------------------------------------------------------------------------

  const dbName = scanAppDatabaseName(namespace);
  const schemaName = dbName;
  const scriptPath = commandScriptPath('cluster/pulumi/canton-network/bigquery-cloudsql.sh');

  const projectId = gcp.organizations.getProjectOutput({}).apply(proj => proj.projectId);

  const commonDependencies = [scan, databaseInstance, replicatorUser];

  // ---------------------------------------------------------------------------
  // 2. Base Arguments Split (Matches Stored Deployment Ordering & Formatting)
  // ---------------------------------------------------------------------------

  // Prefix arguments (Arguments 1–6)
  const baseArgsPrefix: pulumi.Input<string>[] = [
    pulumi.interpolate`--private-network-project="${projectId}"`,
    pulumi.interpolate`--compute-region="${cloudsdkComputeRegion()}"`,
    pulumi.interpolate`--service-account-email="${databaseInstance.serviceAccountEmailAddress}"`,
    pulumi.interpolate`--schema-name="${schemaName}"`,
    pulumi.interpolate`--tables-to-replicate-joined="${tablesToReplicate.join(', ')}"`,
    pulumi.interpolate`--postgres-user-name="${defaultUserName}"`,
  ];

  // Suffix arguments (Arguments 9–12)
  const baseArgsSuffix: pulumi.Input<string>[] = [
    pulumi.interpolate`--replicator-user-name="${replicatorUserName}"`,
    pulumi.interpolate`--postgres-instance-name="${databaseInstance.name}"`,
    pulumi.interpolate`--scan-app-database-name="${dbName}"`,
    pulumi.interpolate`--flyway-migration-to-wait-for="${flywayMigrationToWaitFor}"`,
  ];

  const buildScriptCommand = (
    action: string,
    slotArgs: pulumi.Input<string>[]
  ): pulumi.Output<string> => {
    const allArgs = [...baseArgsPrefix, ...slotArgs, ...baseArgsSuffix];

    return pulumi.all(allArgs).apply(args => {
      const formattedArgs = args.join(' \\\n      ');
      return `'${scriptPath}' ${action} \\\n      ${formattedArgs} \\\n      `;
    });
  };

  // ---------------------------------------------------------------------------
  // 3. Legacy Datastream Slot (Slot 1)
  // ---------------------------------------------------------------------------

  let slot1: command.local.Command | undefined;

  if (enableLegacy) {
    const slot1Args: pulumi.Input<string>[] = [
      pulumi.interpolate`--publication-name="${publicationName}"`,
      pulumi.interpolate`--replication-slot-name="${replicationSlotName}"`,
    ];

    slot1 = new command.local.Command(
      `${namespace.logicalName}-${replicatorUserName}-pub-replicate-slots`,
      {
        create: buildScriptCommand('create-pub-rep-slot', slot1Args),
        delete: buildScriptCommand('delete-pub-rep-slot', slot1Args),
      },
      {
        dependsOn: [databaseInstance, replicatorUser, ...(scan !== undefined ? [scan] : [])],
        deleteBeforeReplace: true,
      }
    );
  }

  // ---------------------------------------------------------------------------
  // 4. Stag-Prod Datastream Slot (Slot 2)
  // ---------------------------------------------------------------------------

  let slot2: command.local.Command | undefined;

  if (enableStagProd) {
    const slot2Args: pulumi.Input<string>[] = [
      pulumi.interpolate`--publication-name="${publicationNameStagProd}"`,
      pulumi.interpolate`--replication-slot-name="${replicationSlotNameStagProd}"`,
    ];

    slot2 = new command.local.Command(
      `${namespace.logicalName}-${replicatorUserName}-pub-replicate-slot-2`,
      {
        create: buildScriptCommand('create-pub-rep-slot', slot2Args),
        delete: buildScriptCommand('delete-pub-rep-slot', slot2Args),
      },
      {
        dependsOn: [databaseInstance, replicatorUser, ...(scan !== undefined ? [scan] : [])],
        deleteBeforeReplace: true,
      }
    );
  }

  // ---------------------------------------------------------------------------
  // 5. Return Created Slots
  // ---------------------------------------------------------------------------

  return {
    slot1,
    slot2,
  };
}
// ============================================================================
// MAIN ENTRYPOINT
// ============================================================================

export async function configureScanBigQuery({
  namespace,
  bigQueryConfig,
  scanReference,
}: ScanBigQueryArgs): Promise<ScanBigQuery> {
  // Use config file to determine which datastreams to enable and their desired states

  const {
    enableLegacyDatastream,
    enableStagProdDatastream,
    legacyDesiredState,
    stagProdDesiredState,
    retentionPeriodSeconds,
  } = bigQueryConfig;

  if (!enableLegacyDatastream && !enableStagProdDatastream) {
    throw new Error(
      'configureScanBigQuery was called, but both legacy and stag-prod Datastreams are disabled.'
    );
  }
  const zone = getCloudSdkZone();
  const [databaseInstance, scanChart] = await (async () => {
    switch (scanReference.type) {
      case 'local':
        return [scanReference.databaseInstance, scanReference.chart];
      case 'external':
        return [await getScanDb(scanReference.databaseInstanceNamePrefix, zone), undefined];
    }
  })();

  const passwordSecret = installReplicatorPassword(namespace);
  const slots = createPublicationAndReplicationSlots(
    namespace,
    databaseInstance,
    createPostgresReplicatorUser(namespace, databaseInstance, passwordSecret),
    scanChart,
    enableLegacyDatastream,
    enableStagProdDatastream
  );

  const natVm = installNatVm(namespace, zone, databaseInstance);
  const pcc = installPrivateConnectivityConfiguration(namespace);
  installDatastreamToNatVmFirewallRule(namespace, pcc, natVm);

  const sourceProfile = installPostgresConnectionProfile(
    namespace,
    databaseInstance,
    scanChart,
    natVm,
    pcc,
    passwordSecret
  );

  // legacy dataset is kept even when the legacy Datastream is disabled
  const legacyDataset = installBigqueryDataset(bigQueryConfig);

  if (enableLegacyDatastream && slots.slot1) {
    const legacyDestinationProfile = installBigqueryConnectionProfile(
      namespace,
      legacyDataset,
      pcc
    );

    installDatastream(
      namespace,
      databaseInstance,
      sourceProfile,
      legacyDestinationProfile,
      legacyDataset,
      slots.slot1,
      legacyDesiredState
    );
  }

  if (enableStagProdDatastream && slots.slot2) {
    const stagingDataset = installBigqueryStagingDataset(bigQueryConfig);
    const prodDataset = installBigqueryProdDataset(bigQueryConfig);
    const stagingDestinationProfile = installBigqueryStagingConnectionProfile(
      namespace,
      stagingDataset,
      pcc
    );

    installDatastream_stag_prod(
      namespace,
      databaseInstance,
      sourceProfile,
      stagingDestinationProfile,
      stagingDataset,
      slots.slot2,
      stagProdDesiredState
    );
    const scheduledQueryContext = installBqScheduledQueryContext();
    installHourlyScheduledQueries(namespace, stagingDataset, prodDataset, scheduledQueryContext);
    installDailyPurgeScheduledQueries(
      namespace,
      stagingDataset,
      scheduledQueryContext,
      retentionPeriodSeconds
    );
  }
  // TODO (DACH-NY/canton-network-internal#6451) not sure if this function needs to return anything,
  // but we need to return something to satisfy the ScanBigQuery type.
  // For now, we return the legacy dataset's ID, as it is always defined.
  // we should consider removing the return datasetId if it's not needed

  return {
    datasetId: legacyDataset.id,
  };
}

export type ScanBigQueryArgs = {
  namespace: ExactNamespace;
  bigQueryConfig: ScanBigQueryConfig;
  scanReference: ScanReference;
};

type ScanReference =
  | {
      type: 'local';
      databaseInstance: gcp.sql.DatabaseInstance;
      chart: InstalledHelmChart;
    }
  | {
      type: 'external';
      databaseInstanceNamePrefix: string;
    };

export type ScanBigQuery = {
  datasetId: pulumi.Output<string>;
};

async function getScanDb(
  instanceNamePrefix: string,
  zone: string
): Promise<gcp.sql.DatabaseInstance> {
  const result = await gcp.sql.getDatabaseInstances({
    project: GCP_PROJECT,
    region: GCP_REGION,
    zone,
  });
  const instanceName =
    result.instances.find(
      instance =>
        instance.name.startsWith(instanceNamePrefix) &&
        instance.settings?.[0]?.userLabels?.cluster === CLUSTER_BASENAME
    )?.name ??
    (() => {
      throw new Error(
        `Could not find SV apps database instance with prefix [${instanceNamePrefix}] and user label [cluster=${CLUSTER_BASENAME}].`
      );
    })();
  return gcp.sql.DatabaseInstance.get(instanceNamePrefix, instanceName);
}

function getLegacyParentUrn(namespace: ExactNamespace): pulumi.URN {
  return `urn:pulumi:canton-network.${CLUSTER_BASENAME}::canton-network::canton:cloud:postgres::${namespace.logicalName}-cn-apps-pg`;
}
