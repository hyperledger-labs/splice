// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as gcp from '@pulumi/gcp';
import * as pulumi from '@pulumi/pulumi';
import * as random from '@pulumi/random';
import * as _ from 'lodash';
import { Resource } from '@pulumi/pulumi';

import { CnChartVersion } from './artifacts';
import { clusterSmallDisk, CloudSqlConfig, config } from './config';
import { spliceConfig } from './config/config';
import { hyperdiskSupportConfig } from './config/hyperdiskSupportConfig';
import {
  appsAffinityAndTolerations,
  infraAffinityAndTolerations,
  installSpliceHelmChart,
} from './helm';
import { installPostgresPasswordSecret } from './secrets';
import { standardStorageClassName } from './storage/storageClass';
import { createVolumeSnapshot } from './storage/volumeSnapshot';
import { ChartValues, CLUSTER_BASENAME, ExactNamespace, GCP_ZONE } from './utils';

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
    cloudSqlConfig: CloudSqlConfig,
    active: boolean = true,
    opts: { disableProtection?: boolean; migrationId?: string; logicalDecoding?: boolean } = {}
  ) {
    const instanceLogicalName = xns.logicalName + '-' + instanceName;
    const instanceLogicalNameAlias = xns.logicalName + '-' + alias; // pulumi name before #12391
    const deletionProtection = opts.disableProtection ? false : cloudSqlConfig.protected;
    const baseOpts = {
      protect: deletionProtection,
      aliases: [{ name: instanceLogicalNameAlias }],
    };
    super('canton:cloud:postgres', instanceLogicalName, undefined, baseOpts);
    this.instanceName = instanceName;
    this.namespace = xns;
    const zoneFromEnv = config.optionalEnv('DB_CLOUDSDK_COMPUTE_ZONE') || GCP_ZONE;
    if (!zoneFromEnv) {
      throw new Error(
        'GCP_ZONE is not set in the environment, and DB_CLOUDSDK_COMPUTE_ZONE is also not set. One of these must be set to specify the zone for the Cloud SQL instance.'
      );
    }
    this.zone = zoneFromEnv;

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
            ...Object.keys(cloudSqlConfig.flags).map(name => {
              return { name, value: cloudSqlConfig.flags[name] };
            }),
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
          tier: cloudSqlConfig.tier,
          edition: cloudSqlConfig.enterprisePlus ? 'ENTERPRISE_PLUS' : 'ENTERPRISE',
          ...(cloudSqlConfig.enterprisePlus
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
    version?: CnChartVersion,
    useInfraAffinityAndTolerations: boolean = false
  ) {
    const logicalName = xns.logicalName + '-' + instanceName;
    const logicalNameAlias = xns.logicalName + '-' + alias; // pulumi name before #12391
    super('canton:network:postgres', logicalName, [], {
      protect: disableProtection ? false : spliceConfig.pulumiProjectConfig.cloudSql.protected,
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
    const supportsHyperdisk = useInfraAffinityAndTolerations
      ? hyperdiskSupportConfig.hyperdiskSupport.enabledForInfra
      : hyperdiskSupportConfig.hyperdiskSupport.enabled;
    const migratingToHyperdisk = useInfraAffinityAndTolerations
      ? hyperdiskSupportConfig.hyperdiskSupport.migratingInfra
      : hyperdiskSupportConfig.hyperdiskSupport.migrating;

    let hyperdiskMigrationValues = {};
    if (supportsHyperdisk && migratingToHyperdisk) {
      const { dataSource } = createVolumeSnapshot({
        resourceName: `pg-data-${xns.logicalName}-${instanceName}-snapshot`,
        snapshotName: `pg-data-${instanceName}-snapshot`,
        namespace: xns.logicalName,
        pvcName: `pg-data-${instanceName}-0`,
      });
      hyperdiskMigrationValues = { dataSource };
    }
    const pg = installSpliceHelmChart(
      xns,
      instanceName,
      'splice-postgres',
      _.merge(values || {}, {
        db: {
          volumeSize: overrideDbSizeFromValues
            ? values?.db?.volumeSize || smallDiskSize
            : smallDiskSize,
          ...(supportsHyperdisk
            ? {
                volumeStorageClass: standardStorageClassName,
                pvcTemplateName: 'pg-data-hd',
                ...hyperdiskMigrationValues,
              }
            : {}),
        },
        persistence: {
          secretName: this.secretName,
        },
      }),
      version,
      {
        aliases: [{ name: logicalNameAlias, type: 'kubernetes:helm.sh/v3:Release' }],
        dependsOn: [passwordSecret],
        ...((supportsHyperdisk &&
          // during the migration we first delete the stateful set, which keeps the old pvcs (stateful sets always keep the pvcs), and then recreate with the new pvcs
          // the stateful sets are immutable so they need to be recreated to force the change of the pvcs
          migratingToHyperdisk) ||
        spliceConfig.pulumiProjectConfig.replacePostgresStatefulSetOnChanges
          ? {
              replaceOnChanges: ['*'],
              deleteBeforeReplace: true,
            }
          : {}),
      },
      true,
      useInfraAffinityAndTolerations ? infraAffinityAndTolerations : appsAffinityAndTolerations
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
  cloudSqlConfig: CloudSqlConfig,
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
  if (cloudSqlConfig.enabled) {
    ret = new CloudPostgres(xns, instanceName, alias, secretName, cloudSqlConfig, o.isActive, {
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
