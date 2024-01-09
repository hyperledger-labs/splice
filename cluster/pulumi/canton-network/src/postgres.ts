import * as gcp from '@pulumi/gcp';
import * as pulumi from '@pulumi/pulumi';
import * as random from '@pulumi/random';
import { Release } from '@pulumi/kubernetes/helm/v3';
import { CustomResourceOptions } from '@pulumi/pulumi';
import {
  clusterLargeDisk,
  envFlag,
  ExactNamespace,
  installCNHelmChart,
  installPostgresPasswordSecret,
  sanitizedForHelm,
} from 'cn-pulumi-common';

const enableCloudSql = envFlag('ENABLE_CLOUD_SQL', false);
export function initDatabase(): string | undefined {
  if (enableCloudSql) {
    return undefined;
  } else {
    return process.env['POSTGRES_DB'] || 'cantonnet';
  }
}

const cluster = process.env['GCP_CLUSTER_BASENAME'] || 'GCP_CLUSTER_BASENAME not set';

const project = gcp.organizations.getProjectOutput({});

// use existing default network (needs to have a private vpc connection)
const privateNetwork = gcp.compute.Network.get(
  'default',
  pulumi.interpolate`https://www.googleapis.com/compute/v1/projects/${project.name}/global/networks/default`
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

  createDatabaseAndInstallMetrics: (name: string, opts?: CustomResourceOptions) => pulumi.Resource;
}

class CloudPostgres extends pulumi.ComponentResource implements Postgres {
  name: string;
  namespace: ExactNamespace;
  address: pulumi.Output<string>;
  secretName: string;

  private readonly pgSvc: gcp.sql.DatabaseInstance;

  constructor(xns: ExactNamespace, name: string, uniqueSecretName: boolean) {
    const logicalName = xns.logicalName + '-' + name;
    super('canton:cloud:postgres', logicalName);
    this.name = name;
    this.namespace = xns;
    this.secretName = uniqueSecretName ? this.name + '-secrets' : 'postgres-secrets';

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
      }
    );

    this.address = this.pgSvc.privateIpAddress;

    const pgDB = this.createDatabaseAndInstallMetrics('cantonnet');

    const password = generatePassword(`${logicalName}-passwd`, { parent: this }).result;
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
      }
    );

    this.registerOutputs({
      privateIpAddress: this.pgSvc.privateIpAddress,
      secretName: this.secretName,
    });
  }

  createDatabaseAndInstallMetrics(name: string, opts?: CustomResourceOptions): pulumi.Resource {
    const db = new gcp.sql.Database(
      `${this.namespace.logicalName}-db-${this.name}-${name}`,
      {
        instance: this.pgSvc.name,
        name: name,
      },
      {
        ...{
          parent: this,
          deletedWith: this.pgSvc,
        },
        ...opts,
      }
    );
    installCNHelmChart(
      this.namespace,
      `${this.name}-${sanitizedForHelm(name)}-e`,
      'cn-postgres-metrics',
      {
        persistence: {
          host: this.address,
          databaseName: name,
          secretName: this.secretName,
        },
      },
      { ...{ dependsOn: [db] }, ...opts }
    );
    return db;
  }
}

class CNPostgres extends pulumi.ComponentResource implements Postgres {
  name: string;
  namespace: ExactNamespace;
  address: pulumi.Output<string>;
  pg: Release;
  secretName: string;

  constructor(xns: ExactNamespace, name: string, uniqueSecretName: boolean) {
    const logicalName = xns.logicalName + '-' + name;
    super('canton:network:postgres', logicalName);

    this.name = name;
    this.namespace = xns;
    this.address = pulumi.output(`${this.name}.${this.namespace.logicalName}.svc.cluster.local`);
    this.secretName = uniqueSecretName ? this.name + '-secrets' : 'postgres-secrets';
    const password = generatePassword(`${logicalName}-passwd`, { parent: this }).result;
    const passwordSecret = installPostgresPasswordSecret(xns, password, this.secretName);

    // there's an "implicit" creation of a database named with the value of the env var POSTGRES_DB
    const pg = installCNHelmChart(
      xns,
      name,
      'cn-postgres',
      {
        db: {
          volumeSize: clusterLargeDisk ? '480Gi' : '240Gi',
        },
        persistence: {
          secretName: this.secretName,
        },
      },
      { dependsOn: [passwordSecret] }
    );
    this.pg = pg;

    this.registerOutputs({
      address: pg.id.apply(() => `${name}.${xns.logicalName}.svc.cluster.local`),
      secretName: this.secretName,
    });
  }

  createDatabaseAndInstallMetrics(name: string, opts?: CustomResourceOptions): pulumi.Resource {
    // when using CNPostgres, apps are expected to create their own database in init containers
    installCNHelmChart(
      this.namespace,
      `${this.name}-${sanitizedForHelm(name)}-e`,
      'cn-postgres-metrics',
      {
        persistence: {
          host: this.address,
          databaseName: name,
          secretName: this.secretName,
        },
      },
      { ...{ dependsOn: [this.pg] }, ...opts }
    );
    return this; // don't return the metrics, because that's a circular dependency
  }
}

// toplevel

export function installPostgres(
  xns: ExactNamespace,
  name: string,
  uniqueSecretName = false
): Postgres {
  let ret: Postgres;
  if (enableCloudSql) {
    ret = new CloudPostgres(xns, name, uniqueSecretName);
  } else {
    ret = new CNPostgres(xns, name, uniqueSecretName);
  }
  return ret;
}
