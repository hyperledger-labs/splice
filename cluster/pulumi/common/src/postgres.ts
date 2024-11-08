import * as gcp from '@pulumi/gcp';
import * as pulumi from '@pulumi/pulumi';
import * as random from '@pulumi/random';
import * as _ from 'lodash';
import { Release } from '@pulumi/kubernetes/helm/v3';

import { config } from './config';
import { activeVersion } from './domainMigration';
import { helmChartNamesPrefix, installSpliceHelmChart } from './helm';
import { installPostgresPasswordSecret } from './secrets';
import { ChartValues, clusterSmallDisk, ExactNamespace, CLUSTER_BASENAME } from './utils';

const enableCloudSql = config.envFlag('ENABLE_CLOUD_SQL', false);
const protectCloudSql = !config.envFlag('DISABLE_CLOUD_SQL_PROTECT', false);
// default tier is equivalent to "Standard" machine with 2 vCpus and 7.5GB RAM
const cloudSqlDbInstance = config.optionalEnv('CLOUDSQL_DB_INSTANCE') || 'db-custom-2-7680';

const project = gcp.organizations.getProjectOutput({});

// use existing default network (needs to have a private vpc connection)
const privateNetwork = gcp.compute.Network.get(
  'default',
  pulumi.interpolate`projects/${project.name}/global/networks/default`
);

function generatePassword(name: string, opts?: pulumi.ResourceOptions): random.RandomPassword {
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

  private readonly pgSvc: gcp.sql.DatabaseInstance;

  constructor(
    xns: ExactNamespace,
    instanceName: string,
    alias: string,
    secretName: string,
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    active: boolean = true,
    disableProtection?: boolean,
    migrationId?: string
  ) {
    const instanceLogicalName = xns.logicalName + '-' + instanceName;
    const instanceLogicalNameAlias = xns.logicalName + '-' + alias; // pulumi name before #12391
    const baseOpts = {
      protect: disableProtection ? false : protectCloudSql,
      aliases: [{ name: instanceLogicalNameAlias }],
    };
    super('canton:cloud:postgres', instanceLogicalName, undefined, baseOpts);
    this.instanceName = instanceName;
    this.namespace = xns;

    this.pgSvc = new gcp.sql.DatabaseInstance(
      instanceLogicalName,
      {
        databaseVersion: 'POSTGRES_14',
        deletionProtection: disableProtection ? false : protectCloudSql,
        region: config.requireEnv('CLOUDSDK_COMPUTE_REGION'),
        settings: {
          activationPolicy: 'ALWAYS', // TODO(#15974): set to NEVER when enabling archived instance
          databaseFlags: [{ name: 'temp_file_limit', value: '100000000' }],
          backupConfiguration: {
            enabled: true,
            pointInTimeRecoveryEnabled: true,
          },
          insightsConfig: {
            queryInsightsEnabled: true,
          },
          tier: cloudSqlDbInstance,
          ipConfiguration: {
            ipv4Enabled: false,
            privateNetwork: privateNetwork.id,
            enablePrivatePathForGoogleCloudServices: true,
          },
          userLabels: migrationId
            ? {
                cluster: CLUSTER_BASENAME,
                migration_id: migrationId,
              }
            : {
                cluster: CLUSTER_BASENAME,
              },
          locationPreference: {
            // it's fairly critical for performance that the sql instance is in the same zone as the GKE nodes
            zone:
              config.optionalEnv('CLOUDSDK_COMPUTE_ZONE') ||
              config.requireEnv('DB_CLOUDSDK_COMPUTE_ZONE'),
          },
        },
      },
      { ...baseOpts, parent: this }
    );

    this.address = this.pgSvc.privateIpAddress;

    const pgDB = new gcp.sql.Database(
      `${this.namespace.logicalName}-db-${this.instanceName}-cantonnet`,
      {
        instance: this.pgSvc.name,
        name: 'cantonnet',
      },
      {
        parent: this,
        deletedWith: this.pgSvc,
        protect: disableProtection ? false : protectCloudSql,
        aliases: [{ name: `${this.namespace.logicalName}-db-${alias}-cantonnet` }],
      }
    );

    const password = generatePassword(`${instanceLogicalName}-passwd`, {
      parent: this,
      protect: disableProtection ? false : protectCloudSql,
      aliases: [{ name: `${instanceLogicalNameAlias}-passwd` }],
    }).result;
    const passwordSecret = installPostgresPasswordSecret(xns, password, secretName);
    this.secretName = passwordSecret.metadata.name;

    new gcp.sql.User(
      `user-${instanceLogicalName}`,
      {
        instance: this.pgSvc.name,
        name: 'cnadmin',
        password: password,
      },
      {
        parent: this,
        deletedWith: pgDB,
        dependsOn: [passwordSecret],
        protect: disableProtection ? false : protectCloudSql,
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
  pg: Release;
  secretName: pulumi.Output<string>;

  constructor(
    xns: ExactNamespace,
    instanceName: string,
    alias: string,
    secretName: string,
    values?: ChartValues,
    overrideDbSizeFromValues?: boolean,
    disableProtection?: boolean
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
      aliases: [
        { name: `${logicalNameAlias}-passwd` },
        // allow for refactoring where the secret was created outside of the resources, can be removed once base version > 0.2.1
        { name: `${logicalName}-passwd`, parent: undefined },
      ],
    }).result;
    const passwordSecret = installPostgresPasswordSecret(xns, password, secretName);
    this.secretName = passwordSecret.metadata.name;

    // an initial database named cantonnet is created automatically (configured in the Helm chart).
    const smallDiskSize = clusterSmallDisk ? '240Gi' : undefined;
    const pg = installSpliceHelmChart(
      xns,
      instanceName,
      `${helmChartNamesPrefix(activeVersion)}-postgres`,
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
      activeVersion,
      {
        aliases: [
          { name: logicalNameAlias, type: 'kubernetes:helm.sh/v3:Release' },
          // can be removed once version is > 0.2.1
          { name: alias, type: 'kubernetes:helm.sh/v3:Release' },
        ],
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
  uniqueSecretName = false,
  isActive: boolean = true,
  migrationId?: number,
  disableProtection?: boolean
): Postgres {
  let ret: Postgres;
  const secretName = uniqueSecretName ? instanceName + '-secrets' : 'postgres-secrets';
  if (enableCloudSql) {
    ret = new CloudPostgres(
      xns,
      instanceName,
      alias,
      secretName,
      isActive,
      disableProtection,
      migrationId?.toString()
    );
  } else {
    ret = new SplicePostgres(xns, instanceName, alias, secretName);
  }
  return ret;
}
