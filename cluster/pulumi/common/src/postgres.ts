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
  opts?: pulumi.ResourceOptions
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
  readonly databaseId?: pulumi.Output<string>;
}

export class CloudPostgres
  extends pulumi.ComponentResource<CloudPostgresOutput>
  implements Postgres
{
  address!: pulumi.Output<string>;
  databaseId?: pulumi.Output<string>;
  databaseInstance!: gcp.sql.DatabaseInstance;
  instanceName!: string;
  namespace!: ExactNamespace;
  secretName!: pulumi.Output<string>;
  user!: gcp.sql.User;
  zone!: string;

  protected async initialize(
    {
      active = true,
      alias,
      cloudSqlConfig,
      disableProtection = false,
      existingInstanceName,
      instanceName,
      logicalDecoding = false,
      migrationId,
      namespace,
      secretName,
      yieldManagement = false,
    }: CloudPostgresArgs,
    opts: pulumi.ComponentResourceOptions | undefined,
    name: string
  ): Promise<CloudPostgresOutput> {
    const deletionProtection = disableProtection ? false : cloudSqlConfig.protected;
    const zoneFromEnv = config.optionalEnv('DB_CLOUDSDK_COMPUTE_ZONE') || GCP_ZONE;
    if (!zoneFromEnv) {
      throw new Error(
        'GCP_ZONE is not set in the environment, and DB_CLOUDSDK_COMPUTE_ZONE is also not set. One of these must be set to specify the zone for the Cloud SQL instance.'
      );
    }
    const zone = zoneFromEnv;

    const databaseInstance = new gcp.sql.DatabaseInstance(
      name,
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
            ...(logicalDecoding ? [{ name: 'cloudsql.logical_decoding', value: 'on' }] : []),
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
          userLabels:
            migrationId !== undefined
              ? {
                  cluster: CLUSTER_BASENAME,
                  migration_id: migrationId.toString(),
                }
              : {
                  cluster: CLUSTER_BASENAME,
                },
          locationPreference: {
            // it's fairly critical for performance that the sql instance is in the same zone as the GKE nodes
            zone: zone,
          },
          maintenanceWindow: spliceConfig.pulumiProjectConfig.cloudSql.maintenanceWindow,
        },
      },
      {
        aliases: opts?.aliases,
        import: existingInstanceName,
        parent: this,
        protect: !yieldManagement && deletionProtection,
        retainOnDelete: yieldManagement,
      }
    );

    const existingDatabase =
      existingInstanceName !== undefined
        ? await gcp.sql.getDatabase({ instance: existingInstanceName, name: 'cantonnet' })
        : undefined;

    new gcp.sql.Database(
      `${namespace.logicalName}-db-${instanceName}-cantonnet`,
      {
        instance: databaseInstance.name,
        name: 'cantonnet',
      },
      {
        parent: this,
        deletedWith: databaseInstance,
        protect: !yieldManagement && deletionProtection,
        retainOnDelete: yieldManagement,
        aliases: [{ name: `${namespace.logicalName}-db-${alias}-cantonnet` }],
        import: existingDatabase?.id,
      }
    );

    const password = generatePassword(`${name}-passwd`, {
      parent: this,
      protect: deletionProtection,
      aliases: [{ name: `${namespace.logicalName}-${alias}-passwd` }],
    }).result;
    const passwordSecret = installPostgresPasswordSecret(
      namespace,
      password,
      secretName,
      existingInstanceName !== undefined,
      yieldManagement
    );

    const userImportOpts =
      existingInstanceName !== undefined
        ? {
            import: `cnadmin//${existingInstanceName}`,
            ignoreChanges: ['password'],
          }
        : {};

    const user = new gcp.sql.User(
      `user-${name}`,
      {
        instance: databaseInstance.name,
        name: 'cnadmin',
        password: password,
      },
      {
        parent: this,
        deletedWith: databaseInstance,
        dependsOn: [passwordSecret],
        protect: !yieldManagement && deletionProtection,
        retainOnDelete: yieldManagement,
        aliases: [{ name: `user-${namespace.logicalName}-${alias}` }],
        ...userImportOpts,
      }
    );

    this.address = databaseInstance.privateIpAddress;
    this.databaseId = databaseInstance.name;
    this.databaseInstance = databaseInstance;
    this.instanceName = instanceName;
    this.namespace = namespace;
    this.secretName = passwordSecret.metadata.name;
    this.user = user;
    this.zone = zone;

    return {
      address: this.address,
      databaseId: this.databaseId,
      secretName: this.secretName,
    };
  }

  private constructor(
    name: string,
    args: CloudPostgresArgs,
    opts?: pulumi.ComponentResourceOptions
  ) {
    super('canton:cloud:postgres', name, args, opts);
  }

  static async install(
    name: string,
    args: CloudPostgresArgs,
    opts?: pulumi.ComponentResourceOptions
  ): Promise<CloudPostgres> {
    const instance = new CloudPostgres(name, args, opts);
    await instance.getData();
    return instance;
  }
}

type CloudPostgresArgs = {
  active?: boolean;
  alias: string;
  cloudSqlConfig: CloudSqlConfig;
  disableProtection?: boolean;
  existingInstanceName?: string;
  instanceName: string;
  logicalDecoding?: boolean;
  migrationId?: number;
  namespace: ExactNamespace;
  secretName: string;
  yieldManagement?: boolean;
};

type CloudPostgresOutput = {
  address: pulumi.Output<string>;
  databaseId?: pulumi.Output<string>;
  secretName: pulumi.Output<string>;
};

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

export async function installPostgres(
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
    existingInstanceName?: string;
    yieldManagement?: boolean;
  } = {}
): Promise<Postgres> {
  const o = { isActive: true, ...opts };
  let ret: Postgres;
  const secretName = uniqueSecretName ? instanceName + '-secrets' : 'postgres-secrets';
  return cloudSqlConfig.enabled
    ? await CloudPostgres.install(
        `${xns.logicalName}-${instanceName}`,
        {
          active: o.isActive,
          alias,
          cloudSqlConfig,
          disableProtection: o.disableProtection,
          existingInstanceName: o.existingInstanceName,
          instanceName,
          logicalDecoding: o.logicalDecoding,
          migrationId: o.migrationId,
          namespace: xns,
          secretName,
          yieldManagement: o.yieldManagement,
        },
        {
          aliases: [{ name: `${xns.logicalName}-${alias}` }],
        }
      )
    : new SplicePostgres(
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
