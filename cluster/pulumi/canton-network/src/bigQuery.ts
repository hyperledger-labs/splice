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
  privateNetwork,
  protectCloudSql,
} from '@lfdecentralizedtrust/splice-pulumi-common/src/postgres';
import {
  ExactNamespace,
  CLUSTER_BASENAME,
  commandScriptPath,
} from '@lfdecentralizedtrust/splice-pulumi-common/src/utils';

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
    // TODO (DACH-NY/canton-network-internal#343) reduce time travel window from 7-day default to 2 days if
    // it makes a cost difference
    labels: {
      cluster: CLUSTER_BASENAME,
    },
  });
}

function installFunctions(): gcp.bigquery.Dataset {
  const datasetName = 'functions';
  const dataset = new gcp.bigquery.Dataset(datasetName, {
    datasetId: datasetName,
    friendlyName: `${datasetName} Dataset`,
    location: cloudsdkComputeRegion(),
    deleteContentsOnDestroy: true,
    labels: {
      cluster: CLUSTER_BASENAME,
    },
  });

  type BQFunction = {
    name: string;
    definitionBody: string;
    arguments: { name: string; dataType: string; arrayElementType?: string }[];
    returnType: string;
  };

  const functions: BQFunction[] = [
    {
      name: 'iso_timestamp',
      definitionBody: "PARSE_TIMESTAMP('%FT%TZ', iso8601_string)",
      arguments: [
        {
          name: 'iso8601_string',
          dataType: 'STRING',
        },
      ],
      returnType: 'TIMESTAMP',
    },
    {
      name: 'daml_prim_path',
      definitionBody: `
        CASE selector
          WHEN 'numeric' THEN '.numeric'
          WHEN 'contractId' THEN '.contractId'
          WHEN 'list' THEN '.list.elements'
          WHEN 'party' THEN '.party'
          -- we treat records just like outer layer;
          -- see how paths start with '$.record'
          WHEN 'record' THEN ''
          ELSE ERROR('Unknown Daml primitive case: ' || selector)
        END
      `,
      arguments: [
        {
          name: 'selector',
          dataType: 'STRING',
        },
      ],
      returnType: 'STRING',
    },
    {
      name: 'daml_record_path',
      definitionBody: `
        -- Return a full JSON path to a nested Daml record field.  A field lookup like
        -- \`.x.y.z\` can be accessed as follows:
        -- 1. Find the record that defines \`x\`.
        -- 2. Find the 0-based index of \`x\` in that record, in order of its fields.
        --    For example, consider it the fourth field (index 3) for this example.
        -- 3. Next, move to the type of the \`x\` field, which should have \`y\`.
        -- 4. Repeat step (2) for \`y\` to find the next index.
        --    In this example, suppose it is the first field (index 0).
        -- 5. Repeat steps (3) and (4) for \`z\`.
        --    In this example, suppose it is the second field (index 1).
        -- 6. The first argument here is \`[3, 0, 1]\` for this example.
        -- 7. Finally, check the type of \`z\`; see \`daml_prim_path\` for a matching
        --    selector to pass here.

      CONCAT('$',
           -- you cannot use SELECT in a BigQuery JSONPath, even indirectly
           CASE ARRAY_LENGTH(field_indices)
             WHEN 0 THEN ''
             WHEN 1 THEN CONCAT('.record.fields[', CAST(field_indices[0] AS STRING), '].value')
             WHEN 2 THEN CONCAT('.record.fields[', CAST(field_indices[0] AS STRING), '].value',
                                '.record.fields[', CAST(field_indices[1] AS STRING), '].value')
             WHEN 3 THEN CONCAT('.record.fields[', CAST(field_indices[0] AS STRING), '].value',
                                '.record.fields[', CAST(field_indices[1] AS STRING), '].value',
                                '.record.fields[', CAST(field_indices[2] AS STRING), '].value')
             ELSE ERROR('Unsupported number of field indices: ' || ARRAY_LENGTH(field_indices))
           END,
           \`da-cn-scratchnet.functions.daml_prim_path\`(prim_selector))

        `,
      arguments: [
        {
          name: 'field_indices',
          dataType: 'ARRAY',
          arrayElementType: 'INT64',
        },
        {
          name: 'prim_selector',
          dataType: 'STRING',
        },
      ],
      returnType: 'STRING',
    },
    {
      name: 'daml_record_numeric',
      definitionBody: `PARSE_BIGNUMERIC(JSON_VALUE(daml_record,
        \`da-cn-scratchnet.functions.daml_record_path\`(path, 'numeric')))`,
      arguments: [
        {
          name: 'daml_record',
          dataType: 'JSON',
        },
        {
          name: 'path',
          dataType: 'ARRAY',
          arrayElementType: 'INT64',
        },
      ],
      returnType: 'BIGNUMERIC',
    },
    {
      name: 'in_time_window',
      definitionBody: `
        (migration_id < migration_id_arg
            OR (migration_id = migration_id_arg
              AND record_time <= UNIX_MICROS(as_of_record_time)))
          AND record_time != -62135596800000000
      `,
      arguments: [
        {
          name: 'as_of_record_time',
          dataType: 'TIMESTAMP',
        },
        {
          name: 'migration_id_arg',
          dataType: 'INT64',
        },
        {
          name: 'record_time',
          dataType: 'INT64',
        },
        {
          name: 'migration_id',
          dataType: 'INT64',
        },
      ],
      returnType: 'BOOL',
    },
    {
      name: `sum_bignumeric_acs`,
      definitionBody: `
        -- Find the ACS as of given time and sum bignumerics at path in the payload.
        (SELECT
          COALESCE(SUM(PARSE_BIGNUMERIC(JSON_VALUE(c.create_arguments,
            \`da-cn-scratchnet.functions.daml_record_path\`(path, 'numeric')))), 0)
        FROM
          \`da-cn-scratchnet.devnet_da2_scan.scan_sv_1_update_history_creates\` c
        WHERE
          NOT EXISTS (
          SELECT
            TRUE
          FROM
            \`da-cn-scratchnet.devnet_da2_scan.scan_sv_1_update_history_exercises\` e
          WHERE
            (e.migration_id < migration_id
              OR (e.migration_id = migration_id
                AND e.record_time <= UNIX_MICROS(as_of_record_time)))
            AND e.consuming
            AND e.template_id_module_name = module_name
            AND e.template_id_entity_name = entity_name
            AND e.contract_id = c.contract_id)
          AND c.template_id_module_name = module_name
          AND c.template_id_entity_name = entity_name
          AND \`da-cn-scratchnet.functions.in_time_window\`(as_of_record_time, migration_id,
                c.record_time, c.migration_id))
      `,
      arguments: [
        { name: 'path', dataType: 'ARRAY', arrayElementType: 'INT64' },
        { name: 'module_name', dataType: 'STRING' },
        { name: 'entity_name', dataType: 'STRING' },
        { name: 'as_of_record_time', dataType: 'TIMESTAMP' },
        { name: 'migration_id', dataType: 'INT64' },
      ],
      returnType: 'BIGNUMERIC',
    },
    {
      name: 'locked',
      definitionBody: `
          \`da-cn-scratchnet.functions.sum_bignumeric_acs\`(
            -- (LockedAmulet) .amulet.amount.initialAmount
            [0, 2, 0],
            'Splice.Amulet',
            'LockedAmulet',
            as_of_record_time,
            migration_id)`,
      arguments: [
        { name: 'as_of_record_time', dataType: 'TIMESTAMP' },
        { name: 'migration_id', dataType: 'INT64' },
      ],
      returnType: 'BIGNUMERIC',
    },
    {
      name: 'unlocked',
      definitionBody: `
          \`da-cn-scratchnet.functions.sum_bignumeric_acs\`(
            -- (Amulet) .amount.initialAmount
            [2, 0],
            'Splice.Amulet',
            'Amulet',
            as_of_record_time,
            migration_id)`,
      arguments: [
        { name: 'as_of_record_time', dataType: 'TIMESTAMP' },
        { name: 'migration_id', dataType: 'INT64' },
      ],
      returnType: 'BIGNUMERIC',
    },
    {
      name: 'unminted',
      definitionBody: `
        \`da-cn-scratchnet.functions.sum_bignumeric_acs\`(
          -- (UnclaimedReward) .amount
          [1],
          'Splice.Amulet',
          'UnclaimedReward',
          as_of_record_time,
          migration_id)
      `,
      arguments: [
        { name: 'as_of_record_time', dataType: 'TIMESTAMP' },
        { name: 'migration_id', dataType: 'INT64' },
      ],
      returnType: 'BIGNUMERIC',
    },
    {
      name: 'TransferSummary_minted',
      definitionBody: `
        \`da-cn-scratchnet.functions.daml_record_numeric\`(tr_json, [0]) -- .inputAppRewardAmount
        + \`da-cn-scratchnet.functions.daml_record_numeric\`(tr_json, [1]) -- .inputValidatorRewardAmount
        + \`da-cn-scratchnet.functions.daml_record_numeric\`(tr_json, [2]) -- .inputSvRewardAmount
      `,
      arguments: [{ name: 'tr_json', dataType: 'JSON' }],
      returnType: 'BIGNUMERIC',
    },
    {
      name: 'choice_result_TransferSummary',
      definitionBody: `
        CASE choice
          WHEN 'AmuletRules_CreateExternalPartySetupProposal'
            -- .transferResult.summary
            THEN JSON_QUERY(result, \`da-cn-scratchnet.functions.daml_record_path\`([3, 1], 'record'))
          WHEN 'AmuletRules_CreateTransferPreapproval'
            -- .transferResult.summary
            THEN JSON_QUERY(result, \`da-cn-scratchnet.functions.daml_record_path\`([1, 1], 'record'))
          WHEN 'AmuletRules_BuyMemberTraffic'
            -- .summary
            THEN JSON_QUERY(result, \`da-cn-scratchnet.functions.daml_record_path\`([1], 'record'))
          WHEN 'AmuletRules_Transfer'
            -- .summary
            THEN JSON_QUERY(result, \`da-cn-scratchnet.functions.daml_record_path\`([1], 'record'))
          WHEN 'TransferPreapproval_Renew'
            -- .transferResult.summary
            THEN JSON_QUERY(result, \`da-cn-scratchnet.functions.daml_record_path\`([1, 1], 'record'))
          ELSE ERROR('no TransferSummary for this choice: ' || choice)
        END
      `,
      arguments: [
        { name: 'choice', dataType: 'STRING' },
        { name: 'result', dataType: 'JSON' },
      ],
      returnType: 'JSON',
    },
    {
      name: 'minted',
      definitionBody: `
        (SELECT
            COALESCE(SUM(\`da-cn-scratchnet.functions.TransferSummary_minted\`(
                      \`da-cn-scratchnet.functions.choice_result_TransferSummary\`(e.choice, e.result))),
                    0)
          FROM
            \`da-cn-scratchnet.devnet_da2_scan.scan_sv_1_update_history_exercises\` e
          WHERE
            -- all the choices that can take coupons as input, and thus mint amulets based on them.
            ((e.choice IN ('AmuletRules_BuyMemberTraffic',
                          'AmuletRules_Transfer',
                          'AmuletRules_CreateTransferPreapproval',
                          'AmuletRules_CreateExternalPartySetupProposal')
                AND e.template_id_entity_name = 'AmuletRules')
                OR (e.choice = 'TransferPreapproval_Renew'
                    AND e.template_id_entity_name = 'TransferPreapproval'))
            AND e.template_id_module_name = 'Splice.AmuletRules'
            AND \`da-cn-scratchnet.functions.in_time_window\`(as_of_record_time, migration_id,
                  e.record_time, e.migration_id))
      `,
      arguments: [
        { name: 'as_of_record_time', dataType: 'TIMESTAMP' },
        { name: 'migration_id', dataType: 'INT64' },
      ],
      returnType: 'BIGNUMERIC',
    },
    {
      name: 'transferresult_fees',
      definitionBody: `
        -- .summary.holdingFees
        PARSE_BIGNUMERIC(JSON_VALUE(tr_json, \`da-cn-scratchnet.functions.daml_record_path\`([1, 5], 'numeric')))
        -- .summary.senderChangeFee
        + PARSE_BIGNUMERIC(JSON_VALUE(tr_json, \`da-cn-scratchnet.functions.daml_record_path\`([1, 7], 'numeric')))
        + (SELECT COALESCE(SUM(PARSE_BIGNUMERIC(JSON_VALUE(x, '$.numeric'))), 0)
          FROM
            UNNEST(JSON_QUERY_ARRAY(tr_json,
                      -- .summary.outputFees
                      \`da-cn-scratchnet.functions.daml_record_path\`([1, 6], 'list'))) AS x)
      `,
      arguments: [{ name: 'tr_json', dataType: 'JSON' }],
      returnType: 'BIGNUMERIC',
    },
    {
      name: 'result_burn',
      definitionBody: `
        CASE choice
          WHEN 'AmuletRules_BuyMemberTraffic' THEN -- Coin Burnt for Purchasing Traffic on the Synchronizer
            -- AmuletRules_BuyMemberTrafficResult
            PARSE_BIGNUMERIC(JSON_VALUE(result, \`da-cn-scratchnet.functions.daml_record_path\`([2], 'numeric'))) -- .amuletPaid
            + PARSE_BIGNUMERIC(JSON_VALUE(result, \`da-cn-scratchnet.functions.daml_record_path\`([1, 5], 'numeric'))) -- .summary.holdingFees
            + PARSE_BIGNUMERIC(JSON_VALUE(result, \`da-cn-scratchnet.functions.daml_record_path\`([1, 7], 'numeric'))) -- .summary.senderChangeFee
          WHEN 'AmuletRules_Transfer' THEN -- Amulet Burnt in Amulet Transfers
            -- TransferResult
            \`da-cn-scratchnet.functions.transferresult_fees\`(result)
          WHEN 'AmuletRules_CreateTransferPreapproval' THEN
            PARSE_BIGNUMERIC(JSON_VALUE(result, \`da-cn-scratchnet.functions.daml_record_path\`([2], 'numeric'))) -- .amuletPaid
            + \`da-cn-scratchnet.functions.transferresult_fees\`(JSON_QUERY(result, \`da-cn-scratchnet.functions.daml_record_path\`([1], 'record'))) -- .transferResult
          WHEN 'AmuletRules_CreateExternalPartySetupProposal' THEN
            PARSE_BIGNUMERIC(JSON_VALUE(result, \`da-cn-scratchnet.functions.daml_record_path\`([4], 'numeric'))) -- .amuletPaid
            + \`da-cn-scratchnet.functions.transferresult_fees\`(JSON_QUERY(result, \`da-cn-scratchnet.functions.daml_record_path\`([3], 'record'))) -- .transferResult
          WHEN 'TransferPreapproval_Renew' THEN
            PARSE_BIGNUMERIC(JSON_VALUE(result, \`da-cn-scratchnet.functions.daml_record_path\`([4], 'numeric'))) -- .amuletPaid
            + \`da-cn-scratchnet.functions.transferresult_fees\`(JSON_QUERY(result, \`da-cn-scratchnet.functions.daml_record_path\`([1], 'record'))) -- .transferResult
          ELSE ERROR('Unknown choice for result_burn: ' || choice)
        END
      `,
      arguments: [
        { name: 'choice', dataType: 'STRING' },
        { name: 'result', dataType: 'JSON' },
      ],
      returnType: 'BIGNUMERIC',
    },
    {
      name: 'burned',
      definitionBody: `
        (SELECT SUM(fees)
          FROM ((
                    SELECT
                        SUM(\`da-cn-scratchnet.functions.result_burn\`(e.choice,
                                                  e.result)) fees
                    FROM
                        \`da-cn-scratchnet.devnet_da2_scan.scan_sv_1_update_history_exercises\` e
                    WHERE
                        ((e.choice IN ('AmuletRules_BuyMemberTraffic',
                                      'AmuletRules_Transfer',
                                      'AmuletRules_CreateTransferPreapproval',
                                      'AmuletRules_CreateExternalPartySetupProposal')
                            AND e.template_id_entity_name = 'AmuletRules')
                            OR (e.choice = 'TransferPreapproval_Renew'
                                AND e.template_id_entity_name = 'TransferPreapproval'))
                      AND e.template_id_module_name = 'Splice.AmuletRules'
                      AND \`da-cn-scratchnet.functions.in_time_window\`(as_of_record_time, migration_id_arg,
                              e.record_time, e.migration_id))
                UNION ALL (-- Purchasing ANS Entries
                    SELECT
                        SUM(PARSE_BIGNUMERIC(JSON_VALUE(c.create_arguments, \`da-cn-scratchnet.functions.daml_record_path\`([2, 0], 'numeric')))) fees -- .amount.initialAmount
                    FROM
                        \`da-cn-scratchnet.devnet_da2_scan.scan_sv_1_update_history_exercises\` e,
                        \`da-cn-scratchnet.devnet_da2_scan.scan_sv_1_update_history_creates\` c
                    WHERE
                        ((e.choice = 'SubscriptionInitialPayment_Collect'
                            AND e.template_id_entity_name = 'SubscriptionInitialPayment'
                            AND c.contract_id = JSON_VALUE(e.result, \`da-cn-scratchnet.functions.daml_record_path\`([2], 'contractId'))) -- .amulet
                            OR (e.choice = 'SubscriptionPayment_Collect'
                                AND e.template_id_entity_name = 'SubscriptionPayment'
                                AND c.contract_id = JSON_VALUE(e.result, \`da-cn-scratchnet.functions.daml_record_path\`([1], 'contractId')))) -- .amulet
                      AND e.template_id_module_name = 'Splice.Wallet.Subscriptions'
                      AND c.template_id_module_name = 'Splice.Amulet'
                      AND c.template_id_entity_name = 'Amulet'
                      AND \`da-cn-scratchnet.functions.in_time_window\`(as_of_record_time, migration_id_arg,
                            e.record_time, e.migration_id)
                      AND c.record_time != -62135596800000000)))
      `,
      arguments: [
        { name: 'as_of_record_time', dataType: 'TIMESTAMP' },
        { name: 'migration_id_arg', dataType: 'INT64' },
      ],
      returnType: 'BIGNUMERIC',
    },
  ];

  functions.map(
    f =>
      new gcp.bigquery.Routine(
        f.name,
        {
          datasetId: dataset.datasetId,
          routineId: f.name,
          routineType: 'SCALAR_FUNCTION',
          language: 'SQL',
          definitionBody: f.definitionBody,
          arguments: f.arguments.map(arg => ({
            name: arg.name,
            dataType: JSON.stringify({
              typeKind: arg.dataType,
              arrayElementType: arg.arrayElementType
                ? { typeKind: arg.arrayElementType }
                : undefined,
            }),
          })),
          returnType: JSON.stringify({
            typeKind: f.returnType,
          }),
        },
        { dependsOn: [dataset] }
      )
  );

  return dataset;
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
      --private-network-project="${privateNetwork.project}" \\
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
  installFunctions();
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
