// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as command from '@pulumi/command';
import * as gcp from '@pulumi/gcp';
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import * as ip from 'ip';
import { InstalledHelmChart, installPostgresPasswordSecret } from 'splice-pulumi-common';
import { config } from 'splice-pulumi-common/src/config';
import {
  Postgres,
  CloudPostgres,
  generatePassword,
  privateNetwork,
  protectCloudSql,
} from 'splice-pulumi-common/src/postgres';
import { ExactNamespace, CLUSTER_BASENAME } from 'splice-pulumi-common/src/utils';

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
    // TODO (#19806) reduce time travel window from 7-day default to 2 days if
    // it makes a cost difference
    labels: {
      cluster: CLUSTER_BASENAME,
    },
  });
}

/* TODO (#19812) remove this comment when enabled on all relevant clusters
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

  // TODO (#19810) may have to await scan migration or pub/rep slots command
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
      vpcPeeringConfig: { subnet: pickDatastreamPeeringCidr(), vpc: privateNetwork.id },
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

// TODO (#19807) if we disable default egress rule, we need another firewall
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

function databaseCommandBracket(postgres: CloudPostgres) {
  return {
    header: pulumi.interpolate`
        set -e
        TMP_BUCKET="da-cn-tmpsql-$(date +%s)-$RANDOM-b"
        TMP_SQL_FILE="$(mktemp tmp_pub_rep_slots_XXXXXXXXXX.sql --tmpdir)"
        GCS_URI="gs://$TMP_BUCKET/$(basename "$TMP_SQL_FILE")"

        if [ -s "$GOOGLE_APPLICATION_CREDENTIALS" ]; then
          echo "Using $GOOGLE_APPLICATION_CREDENTIALS for authentication"
          gcloud auth activate-service-account --key-file="$GOOGLE_APPLICATION_CREDENTIALS"
        elif [ -n "$GOOGLE_CREDENTIALS" ]; then
          echo "Using GOOGLE_CREDENTIALS for authentication"
          echo "$GOOGLE_CREDENTIALS" | gcloud auth activate-service-account --key-file=-
        else
          echo 'No GCP credentials found, using default'
        fi
        echo 'Current gcloud login:'
        gcloud auth list --format=config

        # create temporary bucket
        echo "Creating temporary bucket $TMP_BUCKET"
        gsutil mb --pap enforced -p "${privateNetwork.project}" \
            -l "${cloudsdkComputeRegion()}" "gs://$TMP_BUCKET"

        # grant DB service account access to the bucket
        echo "Granting CloudSQL DB access to $TMP_BUCKET"
        gsutil iam ch "serviceAccount:${postgres.databaseInstance.serviceAccountEmailAddress}:roles/storage.objectAdmin" \
            "gs://$TMP_BUCKET"

        cat > "$TMP_SQL_FILE" <<'EOT'
  `,
    footer: pulumi.interpolate`
EOT

        echo 'Uploading SQL to temporary bucket'
        gsutil cp "$TMP_SQL_FILE" "$GCS_URI"

        echo 'Importing into CloudSQL'
        gcloud sql import sql ${postgres.databaseInstance.name} "$GCS_URI" \
          --database="${scanAppDatabaseName(postgres)}" \
          --user="${postgres.user.name}" \
          --quiet

        echo 'Cleaning up temporary GCS object and bucket'
        gsutil rm "$GCS_URI"
        gsutil rb "gs://$TMP_BUCKET"
        rm "$TMP_SQL_FILE"
  `,
  };
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
  const { header, footer } = databaseCommandBracket(postgres);
  return new command.local.Command(
    `${postgres.namespace.logicalName}-${replicatorUserName}-pub-replicate-slots`,
    {
      // TODO (#19809) refactor to invoke external shell script
      // ----
      // from https://cloud.google.com/datastream/docs/configure-cloudsql-psql
      create: pulumi.interpolate`
        ${header}
          DO $$
          DECLARE
            migration_complete BOOLEAN := FALSE;
            max_attempts INT := 30; -- Try for 5 minutes (30 attempts * 10 seconds)
            attempt INT := 0;
          BEGIN
            WHILE NOT migration_complete AND attempt < max_attempts LOOP
              -- Check if all tables exist AND have the record_time column
              -- this is added by V037__denormalize_update_history.sql
              SELECT COUNT(*) = ${tablesToReplicate.length} INTO migration_complete
                FROM information_schema.columns
                WHERE table_catalog = '${dbName}'
                  AND table_schema = '${schemaName}'
                  AND table_name IN (${tablesToReplicate.map(n => `'${n}'`).join(', ')})
                  AND column_name = 'record_time';

              IF NOT migration_complete THEN
                RAISE NOTICE 'Waiting for update_history tables (attempt %/%), sleeping 10s...', attempt + 1, max_attempts;
                PERFORM pg_sleep(10);
                attempt := attempt + 1;
              END IF;
            END LOOP;

            IF NOT migration_complete THEN
              RAISE EXCEPTION 'Timed out waiting for update_history tables to be created';
            END IF;
          END $$;
          SET search_path TO ${schemaName};
          ALTER USER ${postgres.user.name} WITH REPLICATION; -- needed to create the replication slot
          DO $$
          BEGIN
            -- TODO (#19811) drop slot, pub if table list doesn't match
            IF NOT EXISTS (SELECT 1 FROM pg_publication WHERE pubname = '${publicationName}') THEN
              CREATE PUBLICATION ${publicationName}
                FOR TABLE ${tablesToReplicate.join(', ')};
            END IF;
          END $$;
          COMMIT; -- otherwise fails with "cannot create logical replication slot
                  -- in transaction that has performed writes"
          DO $$
          BEGIN
            IF NOT EXISTS (SELECT 1 FROM pg_replication_slots WHERE slot_name = '${replicationSlotName}') THEN
              PERFORM PG_CREATE_LOGICAL_REPLICATION_SLOT
                ('${replicationSlotName}', 'pgoutput');
            END IF;
          END $$;
          COMMIT;
          ALTER USER ${replicatorUserName} WITH REPLICATION;
          GRANT SELECT ON ALL TABLES
            IN SCHEMA ${schemaName} TO ${replicatorUserName};
          GRANT USAGE ON SCHEMA ${schemaName} TO ${replicatorUserName};
          ALTER DEFAULT PRIVILEGES IN SCHEMA ${schemaName}
            GRANT SELECT ON TABLES TO ${replicatorUserName};
          COMMIT;
        ${footer}
      `,
      delete: pulumi.interpolate`
        ${header}
        DO $$
        BEGIN
          IF EXISTS (SELECT 1 FROM pg_replication_slots WHERE slot_name = '${replicationSlotName}') THEN
            PERFORM PG_DROP_REPLICATION_SLOT('${replicationSlotName}');
          END IF;
        END $$;
        DO $$
        BEGIN
          IF EXISTS (SELECT 1 FROM pg_publication WHERE pubname = '${publicationName}') THEN
            DROP PUBLICATION ${publicationName};
          END IF;
        END $$;
        ${footer}
      `,
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
  installDatastream(postgres, sourceProfile, destinationProfile, dataset, pubRepSlots);
  return;
}
