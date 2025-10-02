// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as command from '@pulumi/command';
import * as gcp from '@pulumi/gcp';
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import * as ip from 'ip';
import {
  InstalledHelmChart,
  installPostgresPasswordSecret,
} from '@lfdecentralizedtrust/splice-pulumi-common';
import { config } from '@lfdecentralizedtrust/splice-pulumi-common/src/config';
import {
  Postgres,
  CloudPostgres,
  generatePassword,
  privateNetworkId,
  protectCloudSql,
} from '@lfdecentralizedtrust/splice-pulumi-common/src/postgres';
import {
  ExactNamespace,
  CLUSTER_BASENAME,
  commandScriptPath,
} from '@lfdecentralizedtrust/splice-pulumi-common/src/utils';

import { allDashboardFunctions, allScanFunctions, computedDataTable } from './bigQuery_functions';

interface ScanBigQueryConfig {
  dataset: string;
  prefix: string;
}

interface PostgresPassword {
  contents: pulumi.Output<string>;
  secret: k8s.core.v1.Secret;
}

const dbPort = 5432;
const replicatorUserName = 'bqdatastream';
const replicationSlotName = 'update_history_datastream_r_slot';
const publicationName = 'update_history_datastream_pub';
// what tables from Scan to replicate to BigQuery
const tablesToReplicate = ['update_history_creates', 'update_history_exercises'];

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

function installNatVm(postgres: CloudPostgres): gcp.compute.Instance {
  const vmName = `${postgres.namespace.logicalName}-nat-vm`;
  // from https://cloud.google.com/datastream/docs/private-connectivity#set-up-reverse-proxy
  const startupScript = pulumi.interpolate`#! /bin/bash

export DB_ADDR=${postgres.address}
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
    zone: postgres.zone,
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

function installDatastream(
  postgres: CloudPostgres,
  source: gcp.datastream.ConnectionProfile,
  destination: gcp.datastream.ConnectionProfile,
  bigQueryDataset: gcp.bigquery.Dataset,
  pubRepSlots: pulumi.Resource
): gcp.datastream.Stream {
  const streamName = `${postgres.namespace.logicalName}-scan-update-history`;
  const schemaName = scanAppDatabaseName(postgres);
  return new gcp.datastream.Stream(
    streamName,
    {
      location: cloudsdkComputeRegion(),
      streamId: streamName,
      displayName: streamName,
      desiredState: 'RUNNING',
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
          // docstring or https://github.com/hyperledger-labs/splice/issues/2011
          dataFreshness: '14400s',
        },
        destinationConnectionProfile: destination.name,
      },
      backfillAll: {},
      labels: {
        cluster: CLUSTER_BASENAME,
      },
    },
    { dependsOn: [postgres, source, destination, bigQueryDataset, pubRepSlots] }
  );
}

function installBigqueryDataset(scanBigQuery: ScanBigQueryConfig): gcp.bigquery.Dataset {
  return new gcp.bigquery.Dataset(scanBigQuery.dataset, {
    datasetId: scanBigQuery.dataset,
    friendlyName: `${scanBigQuery.dataset} Dataset`,
    location: cloudsdkComputeRegion(),
    deleteContentsOnDestroy: true,
    // TODO (DACH-NY/canton-network-internal#343) reduce time travel window from 7-day default to 2 days if
    // it makes a cost difference
    labels: {
      cluster: CLUSTER_BASENAME,
    },
  });
}

function installDashboardsDataset(): gcp.bigquery.Dataset {
  const datasetName = 'dashboards';
  const dataset = new gcp.bigquery.Dataset(datasetName, {
    datasetId: datasetName,
    friendlyName: `${datasetName} Dataset`,
    location: cloudsdkComputeRegion(),
    deleteContentsOnDestroy: true,
    labels: {
      cluster: CLUSTER_BASENAME,
    },
  });

  computedDataTable.toPulumi(
    dataset,
    // TODO(DACH-NY/canton-network-internal#1461) consider making deletionProtection configurable
    false
  );

  return dataset;
}

function installFunctions(
  scanDataset: gcp.bigquery.Dataset,
  dashboardsDataset: gcp.bigquery.Dataset,
  dependsOn: pulumi.Resource[]
): gcp.bigquery.Dataset {
  const datasetName = 'functions';
  const functionsDataset = new gcp.bigquery.Dataset(datasetName, {
    datasetId: datasetName,
    friendlyName: `${datasetName} Dataset`,
    location: cloudsdkComputeRegion(),
    deleteContentsOnDestroy: true,
    labels: {
      cluster: CLUSTER_BASENAME,
    },
  });

  scanDataset.project.apply(project => {
    // We don't just run allFunctions.map() because we want to sequence the creation, since every function
    // might depend on those before it.
    let lastResource: pulumi.Resource | undefined = undefined;
    for (const f in allScanFunctions) {
      lastResource = allScanFunctions[f].toPulumi(
        project,
        functionsDataset,
        functionsDataset,
        scanDataset,
        dashboardsDataset,
        lastResource
          ? [lastResource]
          : [...dependsOn, functionsDataset, scanDataset, dashboardsDataset]
      );
    }

    for (const f in allDashboardFunctions) {
      lastResource = allDashboardFunctions[f].toPulumi(
        project,
        dashboardsDataset,
        functionsDataset,
        scanDataset,
        dashboardsDataset,
        lastResource
          ? [lastResource]
          : [...dependsOn, functionsDataset, scanDataset, dashboardsDataset]
      );
    }
  });

  return functionsDataset;
}

function installScheduledTasks(
  dashboardsDataset: gcp.bigquery.Dataset,
  dependsOn: pulumi.Resource[]
): void {
  pulumi
    .all([dashboardsDataset.project, dashboardsDataset.datasetId])
    .apply(([project, dataset]) => {
      new gcp.bigquery.DataTransferConfig(
        'scheduled_dashboard_update',
        {
          displayName: 'scheduled_dashboard_update',
          dataSourceId: 'scheduled_query',
          schedule: 'every day 13:00', // UTC
          location: cloudsdkComputeRegion(),
          serviceAccountName: `bigquery@${project}.iam.gserviceaccount.com`,
          params: {
            query: `CALL \`${project}.${dataset}.fill_all_stats\`();`,
          },
        },
        { dependsOn: dependsOn }
      );
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

function installBigqueryConnectionProfile(
  postgres: CloudPostgres,
  bigQuery: gcp.bigquery.Dataset,
  pcc: gcp.datastream.PrivateConnection
): gcp.datastream.ConnectionProfile {
  const profileName = `${postgres.namespace.logicalName}-scan-bq-cxn`;
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

function scanAppDatabaseName(postgres: Postgres) {
  return `scan_${postgres.namespace.logicalName.replace(/-/g, '_')}`;
}

function installPostgresConnectionProfile(
  postgres: CloudPostgres,
  scan: InstalledHelmChart,
  natVm: gcp.compute.Instance,
  connection: gcp.datastream.PrivateConnection,
  replicatorPassword: PostgresPassword
): gcp.datastream.ConnectionProfile {
  const profileName = `${postgres.namespace.logicalName}-scan-update-history-cxn`;

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
        database: scanAppDatabaseName(postgres),
      },
      privateConnectivity: {
        privateConnection: connection.name,
      },
      labels: {
        cluster: CLUSTER_BASENAME,
      },
    },
    { dependsOn: [natVm, connection, postgres.databaseInstance, scan] }
  );
}

function installPrivateConnectivityConfiguration(
  postgres: CloudPostgres
): gcp.datastream.PrivateConnection {
  const privateConnectionName = `${postgres.namespace.logicalName}-scan-update-history-datastream-vpc`;
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
    sourceRanges: [source.vpcPeeringConfig.subnet],
    destinationRanges: [natVm.networkInterfaces[0].networkIp],
  });
}

// TODO (DACH-NY/canton-network-internal#342) if we disable default egress rule, we need another firewall
// rule for Nat VM -> Postgres

function installReplicatorPassword(postgres: CloudPostgres): PostgresPassword {
  const secretName = `${postgres.namespace.logicalName}-${replicatorUserName}-passwd`;
  const password = generatePassword(`${postgres.instanceName}-${replicatorUserName}-passwd`, {
    parent: postgres,
    protect: protectCloudSql,
  }).result;
  return {
    contents: password,
    secret: installPostgresPasswordSecret(postgres.namespace, password, secretName),
  };
}

function createPostgresReplicatorUser(
  postgres: CloudPostgres,
  password: PostgresPassword
): gcp.sql.User {
  const name = `${postgres.namespace.logicalName}-user-${replicatorUserName}`;
  return new gcp.sql.User(
    name,
    {
      instance: postgres.databaseInstance.name,
      name: replicatorUserName,
      password: password.contents,
    },
    {
      parent: postgres,
      deletedWith: postgres.databaseInstance,
      retainOnDelete: true,
      protect: protectCloudSql,
      dependsOn: [postgres.databaseInstance, password.secret],
    }
  );
}

/*
For the SQL below to apply, the user/operator applying the pulumi
needs the 'Cloud SQL Editor' IAM role in the relevant GCP project
 */

function createPublicationAndReplicationSlots(
  postgres: CloudPostgres,
  replicatorUser: gcp.sql.User,
  scan: InstalledHelmChart
) {
  const dbName = scanAppDatabaseName(postgres);
  const schemaName = dbName;
  const path = commandScriptPath('cluster/pulumi/canton-network/bigquery-cloudsql.sh');
  const scriptArgs = pulumi.interpolate`\\
      --private-network-project="${gcp.organizations.getProjectOutput({}).apply(proj => proj.name)}" \\
      --compute-region="${cloudsdkComputeRegion()}" \\
      --service-account-email="${postgres.databaseInstance.serviceAccountEmailAddress}" \\
      --tables-to-replicate-length="${tablesToReplicate.length}" \\
      --db-name="${dbName}" \\
      --schema-name="${schemaName}" \\
      --tables-to-replicate-list="${tablesToReplicate.map(n => `'${n}'`).join(', ')}" \\
      --tables-to-replicate-joined="${tablesToReplicate.join(', ')}" \\
      --postgres-user-name="${postgres.user.name}" \\
      --publication-name="${publicationName}" \\
      --replication-slot-name="${replicationSlotName}" \\
      --replicator-user-name="${replicatorUserName}" \\
      --postgres-instance-name="${postgres.databaseInstance.name}" \\
      --scan-app-database-name="${scanAppDatabaseName(postgres)}"`;
  return new command.local.Command(
    `${postgres.namespace.logicalName}-${replicatorUserName}-pub-replicate-slots`,
    {
      create: pulumi.interpolate`'${path}' create-pub-rep-slot ${scriptArgs}`,
      delete: pulumi.interpolate`'${path}' delete-pub-rep-slot ${scriptArgs}`,
    },
    {
      deletedWith: postgres.databaseInstance,
      dependsOn: [scan, postgres.databaseInstance, replicatorUser],
    }
  );
}

export function configureScanBigQuery(
  postgres: CloudPostgres,
  scanBigQuery: ScanBigQueryConfig,
  scan: InstalledHelmChart
): void {
  const passwordSecret = installReplicatorPassword(postgres);
  const pubRepSlots = createPublicationAndReplicationSlots(
    postgres,
    createPostgresReplicatorUser(postgres, passwordSecret),
    scan
  );

  const natVm = installNatVm(postgres);
  const dataset = installBigqueryDataset(scanBigQuery);
  const pcc = installPrivateConnectivityConfiguration(postgres);
  const destinationProfile = installBigqueryConnectionProfile(postgres, dataset, pcc);
  const sourceProfile = installPostgresConnectionProfile(
    postgres,
    scan,
    natVm,
    pcc,
    passwordSecret
  );
  installDatastreamToNatVmFirewallRule(postgres.namespace, pcc, natVm);
  const stream = installDatastream(
    postgres,
    sourceProfile,
    destinationProfile,
    dataset,
    pubRepSlots
  );
  const dashboardsDataset = installDashboardsDataset();
  const functionsDataset = installFunctions(dataset, dashboardsDataset, [stream]);
  installScheduledTasks(dashboardsDataset, [functionsDataset, dataset]);
  return;
}
