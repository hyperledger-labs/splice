import * as gcp from '@pulumi/gcp';
import * as pulumi from '@pulumi/pulumi';
import * as random from '@pulumi/random';
import * as _ from 'lodash';
import { Release } from '@pulumi/kubernetes/helm/v3';

import { config } from './config';
import { defaultVersion, installSpliceHelmChart } from './helm';
import { installPostgresPasswordSecret } from './secrets';
import { ChartValues, clusterSmallDisk, ExactNamespace, CLUSTER_BASENAME } from './utils';

const enableCloudSql = config.envFlag('ENABLE_CLOUD_SQL', false);
const protectCloudSql = !config.envFlag('DISABLE_CLOUD_SQL_PROTECT', false);

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
  readonly secretName: string;
}

export class CloudPostgres extends pulumi.ComponentResource implements Postgres {
  instanceName: string;
  namespace: ExactNamespace;
  address: pulumi.Output<string>;
  secretName: string;

  private readonly pgSvc: gcp.sql.DatabaseInstance;

  constructor(
    xns: ExactNamespace,
    instanceName: string,
    alias: string,
    secretName: string,
    active: boolean = true
  ) {
    const instanceLogicalName = xns.logicalName + '-' + instanceName;
    const instanceLogicalNameAlias = xns.logicalName + '-' + alias; // pulumi name before #12391
    const baseOpts = {
      protect: protectCloudSql,
      aliases: [{ name: instanceLogicalNameAlias }],
    };
    super('canton:cloud:postgres', instanceLogicalName, undefined, baseOpts);
    this.instanceName = instanceName;
    this.namespace = xns;
    this.secretName = secretName;

    this.pgSvc = new gcp.sql.DatabaseInstance(
      instanceLogicalName,
      {
        databaseVersion: 'POSTGRES_14',
        deletionProtection: protectCloudSql,
        region: config.requireEnv('CLOUDSDK_COMPUTE_REGION'),
        settings: {
          activationPolicy: active ? 'ALWAYS' : 'NEVER',
          databaseFlags: [{ name: 'temp_file_limit', value: '100000000' }],
          backupConfiguration: {
            enabled: true,
            pointInTimeRecoveryEnabled: true,
          },
          insightsConfig: {
            queryInsightsEnabled: true,
          },
          // tier is equivalent to "Standard" machine with 2 vCpus and 7.5GB RAM
          tier: 'db-custom-2-7680',
          ipConfiguration: {
            ipv4Enabled: false,
            privateNetwork: privateNetwork.id,
            enablePrivatePathForGoogleCloudServices: true,
          },
          userLabels: {
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
        protect: protectCloudSql,
        aliases: [{ name: `${this.namespace.logicalName}-db-${alias}-cantonnet` }],
      }
    );

    const password = generatePassword(`${instanceLogicalName}-passwd`, {
      parent: this,
      protect: protectCloudSql,
      aliases: [{ name: `${instanceLogicalNameAlias}-passwd` }],
    }).result;
    const passwordSecret = installPostgresPasswordSecret(xns, password, this.secretName);

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
        protect: protectCloudSql,
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
  secretName: string;

  constructor(
    xns: ExactNamespace,
    instanceName: string,
    alias: string,
    secretName: string,
    values?: ChartValues
  ) {
    const logicalName = xns.logicalName + '-' + instanceName;
    const logicalNameAlias = xns.logicalName + '-' + alias; // pulumi name before #12391
    super('canton:network:postgres', logicalName, [], {
      protect: protectCloudSql,
      aliases: [{ name: logicalNameAlias, type: 'canton:network:postgres' }],
    });

    this.instanceName = instanceName;
    this.namespace = xns;
    this.secretName = secretName;
    this.address = pulumi.output(
      `${this.instanceName}.${this.namespace.logicalName}.svc.cluster.local`
    );
    const password = generatePassword(`${logicalName}-passwd`, {
      parent: this,
      aliases: [{ name: `${logicalNameAlias}-passwd` }],
    }).result;
    const passwordSecret = installPostgresPasswordSecret(xns, password, this.secretName);

    // an initial database named cantonnet is created automatically (configured in the Helm chart).
    const pg = installSpliceHelmChart(
      xns,
      instanceName,
      'cn-postgres',
      _.merge(values || {}, {
        db: {
          volumeSize: clusterSmallDisk ? '240Gi' : undefined,
        },
        persistence: {
          secretName: this.secretName,
        },
      }),
      defaultVersion,
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
  uniqueSecretName = false,
  isActive: boolean = true
): Postgres {
  let ret: Postgres;
  const secretName = uniqueSecretName ? instanceName + '-secrets' : 'postgres-secrets';
  if (enableCloudSql) {
    ret = new CloudPostgres(xns, instanceName, alias, secretName, isActive);
  } else {
    ret = new SplicePostgres(xns, instanceName, alias, secretName);
  }
  return ret;
}
