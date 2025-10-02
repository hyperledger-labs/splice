// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as gcp from '@pulumi/gcp';
import * as pulumi from '@pulumi/pulumi';
import * as random from '@pulumi/random';
import * as _ from 'lodash';
import { Resource } from '@pulumi/pulumi';

import { CnChartVersion } from './artifacts';
import { clusterSmallDisk, config } from './config';
import { spliceConfig } from './config/config';
import { installSpliceHelmChart } from './helm';
import { installPostgresPasswordSecret } from './secrets';
import { ChartValues, CLUSTER_BASENAME, ExactNamespace, GCP_ZONE } from './utils';

const enableCloudSql = spliceConfig.pulumiProjectConfig.cloudSql.enabled;
export const protectCloudSql = spliceConfig.pulumiProjectConfig.cloudSql.protected;
const cloudSqlDbInstance = spliceConfig.pulumiProjectConfig.cloudSql.tier;
const cloudSqlEnterprisePlus = spliceConfig.pulumiProjectConfig.cloudSql.enterprisePlus;

const project = gcp.organizations.getProjectOutput({});

// use existing default network (needs to have a private vpc connection)
export const privateNetworkId = pulumi.interpolate`projects/${project.name}/global/networks/default`;

export function generatePassword(
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

export interface Postgres extends pulumi.Resource {
  readonly instanceName: string;
  readonly namespace: ExactNamespace;

  readonly address: pulumi.Output<string>;
  readonly secretName: pulumi.Output<string>;
}

export class CloudPostgres extends pulumi.ComponentResource implements Postgres {
  instanceName: string;
  namespace: ExactNamespace;
  address: pulumi.Output<string>;
  secretName: pulumi.Output<string>;
  user: gcp.sql.User;
  zone: string;

  private readonly pgSvc: gcp.sql.DatabaseInstance;
  // type-limited view of pgSvc
  readonly databaseInstance: pulumi.Resource &
    Pick<gcp.sql.DatabaseInstance, 'name' | 'serviceAccountEmailAddress'>;

  constructor(
    xns: ExactNamespace,
    instanceName: string,
    alias: string,
    secretName: string,
    active: boolean = true,
    opts: { disableProtection?: boolean; migrationId?: string; logicalDecoding?: boolean } = {}
  ) {
    const instanceLogicalName = xns.logicalName + '-' + instanceName;
    const instanceLogicalNameAlias = xns.logicalName + '-' + alias; // pulumi name before #12391
    const deletionProtection = opts.disableProtection ? false : protectCloudSql;
    const baseOpts = {
      protect: deletionProtection,
      aliases: [{ name: instanceLogicalNameAlias }],
    };
    super('canton:cloud:postgres', instanceLogicalName, undefined, baseOpts);
    this.instanceName = instanceName;
    this.namespace = xns;
    this.zone = GCP_ZONE || config.requireEnv('DB_CLOUDSDK_COMPUTE_ZONE');

    this.databaseInstance = this.pgSvc = new gcp.sql.DatabaseInstance(
      instanceLogicalName,
      {
        databaseVersion: 'POSTGRES_14',
        deletionProtection: deletionProtection,
        region: config.requireEnv('CLOUDSDK_COMPUTE_REGION'),
        settings: {
          deletionProtectionEnabled: deletionProtection,
          activationPolicy: active ? 'ALWAYS' : 'NEVER',
          databaseFlags: [
            { name: 'temp_file_limit', value: '2147483647' },
            ...(opts.logicalDecoding ? [{ name: 'cloudsql.logical_decoding', value: 'on' }] : []),
          ],
          backupConfiguration: {
            enabled: true,
            pointInTimeRecoveryEnabled: true,
            ...(spliceConfig.pulumiProjectConfig.cloudSql.backupsToRetain
              ? {
                  backupRetentionSettings: {
                    retainedBackups: spliceConfig.pulumiProjectConfig.cloudSql.backupsToRetain,
                  },
                }
              : {}),
          },
          insightsConfig: {
            queryInsightsEnabled: true,
          },
          tier: cloudSqlDbInstance,
          edition: cloudSqlEnterprisePlus ? 'ENTERPRISE_PLUS' : 'ENTERPRISE',
          ...(cloudSqlEnterprisePlus
            ? {
                dataCacheConfig: {
                  dataCacheEnabled: true,
                },
              }
            : undefined),
          ipConfiguration: {
            ipv4Enabled: false,
            privateNetwork: privateNetworkId,
            enablePrivatePathForGoogleCloudServices: true,
          },
          userLabels: opts.migrationId
            ? {
                cluster: CLUSTER_BASENAME,
                migration_id: opts.migrationId,
              }
            : {
                cluster: CLUSTER_BASENAME,
              },
          locationPreference: {
            // it's fairly critical for performance that the sql instance is in the same zone as the GKE nodes
            zone: this.zone,
          },
          maintenanceWindow: spliceConfig.pulumiProjectConfig.cloudSql.maintenanceWindow,
        },
      },
      { ...baseOpts, parent: this }
    );

    this.address = this.pgSvc.privateIpAddress;

    new gcp.sql.Database(
      `${this.namespace.logicalName}-db-${this.instanceName}-cantonnet`,
      {
        instance: this.pgSvc.name,
        name: 'cantonnet',
      },
      {
        parent: this,
        deletedWith: this.pgSvc,
        protect: deletionProtection,
        aliases: [{ name: `${this.namespace.logicalName}-db-${alias}-cantonnet` }],
      }
    );

    const password = generatePassword(`${instanceLogicalName}-passwd`, {
      parent: this,
      protect: deletionProtection,
      aliases: [{ name: `${instanceLogicalNameAlias}-passwd` }],
    }).result;
    const passwordSecret = installPostgresPasswordSecret(xns, password, secretName);
    this.secretName = passwordSecret.metadata.name;

    this.user = new gcp.sql.User(
      `user-${instanceLogicalName}`,
      {
        instance: this.pgSvc.name,
        name: 'cnadmin',
        password: password,
      },
      {
        parent: this,
        deletedWith: this.pgSvc,
        dependsOn: [passwordSecret],
        protect: deletionProtection,
        aliases: [{ name: `user-${instanceLogicalNameAlias}` }],
      }
    );

    this.registerOutputs({
      privateIpAddress: this.pgSvc.privateIpAddress,
      secretName: this.secretName,
    });
  }
}

export class SplicePostgres extends pulumi.ComponentResource implements Postgres {
  instanceName: string;
  namespace: ExactNamespace;
  address: pulumi.Output<string>;
  pg: Resource;
  secretName: pulumi.Output<string>;

  constructor(
    xns: ExactNamespace,
    instanceName: string,
    alias: string,
    secretName: string,
    values?: ChartValues,
    overrideDbSizeFromValues?: boolean,
    disableProtection?: boolean,
    version?: CnChartVersion
  ) {
    const logicalName = xns.logicalName + '-' + instanceName;
    const logicalNameAlias = xns.logicalName + '-' + alias; // pulumi name before #12391
    super('canton:network:postgres', logicalName, [], {
      protect: disableProtection ? false : protectCloudSql,
      aliases: [{ name: logicalNameAlias, type: 'canton:network:postgres' }],
    });

    this.instanceName = instanceName;
    this.namespace = xns;
    this.address = pulumi.output(
      `${this.instanceName}.${this.namespace.logicalName}.svc.cluster.local`
    );
    const password = generatePassword(`${logicalName}-passwd`, {
      parent: this,
      aliases: [{ name: `${logicalNameAlias}-passwd` }],
    }).result;
    const passwordSecret = installPostgresPasswordSecret(xns, password, secretName);
    this.secretName = passwordSecret.metadata.name;

    // an initial database named cantonnet is created automatically (configured in the Helm chart).
    const smallDiskSize = clusterSmallDisk ? '240Gi' : undefined;
    const pg = installSpliceHelmChart(
      xns,
      instanceName,
      'splice-postgres',
      _.merge(values || {}, {
        db: {
          volumeSize: overrideDbSizeFromValues
            ? values?.db?.volumeSize || smallDiskSize
            : smallDiskSize,
        },
        persistence: {
          secretName: this.secretName,
        },
      }),
      version,
      {
        aliases: [{ name: logicalNameAlias, type: 'kubernetes:helm.sh/v3:Release' }],
        dependsOn: [passwordSecret],
      }
    );
    this.pg = pg;

    this.registerOutputs({
      address: pg.id.apply(() => `${instanceName}.${xns.logicalName}.svc.cluster.local`),
      secretName: this.secretName,
    });
  }
}

// toplevel

export function installPostgres(
  xns: ExactNamespace,
  instanceName: string,
  alias: string,
  version: CnChartVersion,
  uniqueSecretName = false,
  opts: {
    isActive?: boolean;
    migrationId?: number;
    disableProtection?: boolean;
    logicalDecoding?: boolean;
  } = {}
): Postgres {
  const o = { isActive: true, ...opts };
  let ret: Postgres;
  const secretName = uniqueSecretName ? instanceName + '-secrets' : 'postgres-secrets';
  if (enableCloudSql) {
    ret = new CloudPostgres(xns, instanceName, alias, secretName, o.isActive, {
      disableProtection: o.disableProtection,
      migrationId: o.migrationId?.toString(),
      logicalDecoding: o.logicalDecoding,
    });
  } else {
    ret = new SplicePostgres(
      xns,
      instanceName,
      alias,
      secretName,
      undefined,
      undefined,
      undefined,
      version
    );
  }
  return ret;
}
