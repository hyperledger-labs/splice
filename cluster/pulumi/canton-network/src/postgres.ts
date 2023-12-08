import * as command from '@pulumi/command';
import * as gcp from '@pulumi/gcp';
import * as pulumi from '@pulumi/pulumi';
import * as random from '@pulumi/random';
import { Release } from '@pulumi/kubernetes/helm/v3';
import {
  clusterLargeDisk,
  envFlag,
  ExactNamespace,
  installCNHelmChart,
  installPostgresPasswordSecret,
  sanitizedForHelm,
} from 'cn-pulumi-common';

const enableCloudSql = envFlag('ENABLE_CLOUD_SQL', false);
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

  createDatabase: (name: string) => pulumi.Resource;
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
    this.name = logicalName;
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

    const pgDB = this.createDatabase('cantonnet');

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

    this.address = this.pgSvc.privateIpAddress;

    this.registerOutputs({
      privateIpAddress: this.pgSvc.privateIpAddress,
      secretName: this.secretName,
    });
  }

  createDatabase(name: string): pulumi.Resource {
    const db = new gcp.sql.Database(
      `db-${this.name}-${name}`,
      {
        instance: this.pgSvc.name,
        name: name,
      },
      {
        parent: this,
        deletedWith: this.pgSvc,
      }
    );
    installCNHelmChart(
      this.namespace,
      `${this.namespace.logicalName}-pg-exporter-${this.name}-${sanitizedForHelm(name)}`,
      'cn-postgres-metrics',
      {
        postgres: {
          hostname: this.address,
          database: name,
        },
        postgresSecretName: this.secretName,
      },
      [db]
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
        postgresSecretName: this.secretName,
      },
      [passwordSecret]
    );
    this.pg = pg;

    this.registerOutputs({
      address: pg.id.apply(() => `${name}.${xns.logicalName}.svc.cluster.local`),
      secretName: this.secretName,
    });
  }

  createDatabase(name: string): pulumi.Resource {
    // the DB is not immediately available even if you can connect to the pod, so psql might fail a few times at the beginning.
    const waitForPostgresToBeUp = new command.local.Command(
      `waitdb-${name}`,
      {
        create:
          `kubectl exec -n ${this.namespace.logicalName} ${this.name}-0 -- ` +
          `bash -c "let RETRIES=60; until psql --username=cnadmin --dbname=\${POSTGRES_DB:-cantonnet} -c \\"select 1\\" > /dev/null 2>&1 || [ $RETRIES -eq 0 ]; do let RETRIES=RETRIES-1; sleep 1; done"`,
      },
      { dependsOn: this.pg }
    );
    const createCommand = new command.local.Command(
      `createdb-${name}`,
      {
        create: `kubectl exec -n ${this.namespace.logicalName} ${this.name}-0 -- psql --username=cnadmin --dbname=\${POSTGRES_DB:-cantonnet} -c "create database ${name}"`,
      },
      { dependsOn: waitForPostgresToBeUp }
    );
    installCNHelmChart(
      this.namespace,
      `${this.namespace.logicalName}-pg-exporter-${this.name}-${sanitizedForHelm(name)}`,
      'cn-postgres-metrics',
      {
        postgres: {
          hostname: this.address,
          database: name,
        },
        postgresSecretName: this.secretName,
      },
      [createCommand]
    );
    return createCommand;
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
