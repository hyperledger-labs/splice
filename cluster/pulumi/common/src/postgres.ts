import * as gcp from '@pulumi/gcp';
import * as pulumi from '@pulumi/pulumi';
import * as random from '@pulumi/random';
import * as _ from 'lodash';
import { Release } from '@pulumi/kubernetes/helm/v3';

import { config } from './config';
import { CNCustomResourceOptions, defaultVersion, installCNHelmChart } from './helm';
import { installPostgresPasswordSecret } from './secrets';
import { ChartValues, clusterSmallDisk, ExactNamespace, sanitizedForHelm } from './utils';

const enableCloudSql = config.envFlag('ENABLE_CLOUD_SQL', false);
const protectCloudSql = !config.envFlag('DISABLE_CLOUD_SQL_PROTECT', false);

const cluster = config.requireEnv('GCP_CLUSTER_BASENAME');

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
  readonly name: string;
  readonly namespace: ExactNamespace;

  readonly address: pulumi.Output<string>;
  readonly secretName: string;
}

export class CloudPostgres extends pulumi.ComponentResource implements Postgres {
  name: string;
  namespace: ExactNamespace;
  address: pulumi.Output<string>;
  secretName: string;

  private readonly pgSvc: gcp.sql.DatabaseInstance;

  constructor(xns: ExactNamespace, name: string, secretName: string) {
    const logicalName = xns.logicalName + '-' + name;
    super('canton:cloud:postgres', logicalName);
    this.name = name;
    this.namespace = xns;
    this.secretName = secretName;

    this.pgSvc = new gcp.sql.DatabaseInstance(
      logicalName,
      {
        databaseVersion: 'POSTGRES_14',
        deletionProtection: false,
        region: 'us-central1',
        settings: {
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
            cluster: cluster,
          },
        },
      },
      {
        parent: this,
        protect: protectCloudSql,
      }
    );

    this.address = this.pgSvc.privateIpAddress;

    const pgDB = new gcp.sql.Database(
      `${this.namespace.logicalName}-db-${this.name}-cantonnet`,
      {
        instance: this.pgSvc.name,
        name: 'cantonnet',
      },
      {
        parent: this,
        deletedWith: this.pgSvc,
        protect: protectCloudSql,
      }
    );

    installPostgresMetrics(this, 'cantonnet', [pgDB], { parent: this });

    const password = generatePassword(`${logicalName}-passwd`, {
      parent: this,
      protect: protectCloudSql,
    }).result;
    const passwordSecret = installPostgresPasswordSecret(xns, password, this.secretName);

    new gcp.sql.User(
      `user-${logicalName}`,
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
      }
    );

    this.registerOutputs({
      privateIpAddress: this.pgSvc.privateIpAddress,
      secretName: this.secretName,
    });
  }
}

export class CNPostgres extends pulumi.ComponentResource implements Postgres {
  name: string;
  namespace: ExactNamespace;
  address: pulumi.Output<string>;
  pg: Release;
  secretName: string;

  constructor(xns: ExactNamespace, name: string, secretName: string, values?: ChartValues) {
    const logicalName = xns.logicalName + '-' + name;
    super('canton:network:postgres', logicalName, [], { protect: protectCloudSql });

    this.name = name;
    this.namespace = xns;
    this.secretName = secretName;
    this.address = pulumi.output(`${this.name}.${this.namespace.logicalName}.svc.cluster.local`);
    const password = generatePassword(`${logicalName}-passwd`, { parent: this }).result;
    const passwordSecret = installPostgresPasswordSecret(xns, password, this.secretName);

    // an initial database named cantonnet is created automatically (configured in the Helm chart).
    const pg = installCNHelmChart(
      xns,
      name,
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
      { dependsOn: [passwordSecret] }
    );
    this.pg = pg;

    this.registerOutputs({
      address: pg.id.apply(() => `${name}.${xns.logicalName}.svc.cluster.local`),
      secretName: this.secretName,
    });
  }
}

// toplevel

export function installPostgresMetrics(
  postgres: Postgres,
  name: string,
  dependsOn: pulumi.Input<pulumi.Resource>[],
  opts?: CNCustomResourceOptions
): Release {
  return installCNHelmChart(
    postgres.namespace,
    `${postgres.name}-${sanitizedForHelm(name)}-e`,
    'cn-postgres-metrics',
    {
      persistence: {
        host: postgres.address,
        databaseName: name,
        secretName: postgres.secretName,
      },
    },
    defaultVersion,
    { ...{ dependsOn: dependsOn }, ...opts }
  );
}

export function installPostgres(
  xns: ExactNamespace,
  name: string,
  uniqueSecretName = false
): Postgres {
  let ret: Postgres;
  const secretName = uniqueSecretName ? name + '-secrets' : 'postgres-secrets';
  if (enableCloudSql) {
    ret = new CloudPostgres(xns, name, secretName);
  } else {
    ret = new CNPostgres(xns, name, secretName);
  }
  return ret;
}
