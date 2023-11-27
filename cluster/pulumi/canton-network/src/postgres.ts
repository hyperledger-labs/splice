import * as command from '@pulumi/command';
import * as gcp from '@pulumi/gcp';
import * as pulumi from '@pulumi/pulumi';
import * as random from '@pulumi/random';
import { Release } from '@pulumi/kubernetes/helm/v3';
import { clusterLargeDisk, envFlag, ExactNamespace, installCNHelmChart } from 'cn-pulumi-common';

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
  readonly password: pulumi.Output<string>;

  createDatabase: (name: string) => pulumi.Resource;
}

class CloudPostgres extends pulumi.ComponentResource implements Postgres {
  name: string;
  namespace: ExactNamespace;
  address: pulumi.Output<string>;
  password: pulumi.Output<string>;

  private readonly pgSvc: gcp.sql.DatabaseInstance;

  constructor(xns: ExactNamespace, name: string) {
    const logicalName = xns.logicalName + '-' + name;
    super('canton:cloud:postgres', logicalName);
    this.name = logicalName;
    this.namespace = xns;

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

    this.password = generatePassword(`${logicalName}-passwd`, { parent: this }).result;

    new gcp.sql.User(
      `user-${logicalName}`,
      {
        instance: this.pgSvc.name,
        name: 'cnadmin',
        password: this.password,
      },
      {
        parent: this,
        deletedWith: pgDB,
      }
    );

    this.address = this.pgSvc.privateIpAddress;

    this.registerOutputs({
      privateIpAddress: this.pgSvc.privateIpAddress,
    });
  }

  createDatabase(name: string): pulumi.Resource {
    return new gcp.sql.Database(
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
  }
}

class CNPostgres extends pulumi.ComponentResource implements Postgres {
  name: string;
  namespace: ExactNamespace;
  address: pulumi.Output<string>;
  password: pulumi.Output<string>;
  pg: Release;

  constructor(xns: ExactNamespace, name: string) {
    const logicalName = xns.logicalName + '-' + name;
    super('canton:network:postgres', logicalName);

    this.name = name;
    this.namespace = xns;
    this.address = pulumi.output(`${this.name}.${this.namespace.logicalName}.svc.cluster.local`);
    this.password = generatePassword(`${logicalName}-passwd`, { parent: this }).result;

    // there's an "implicit" creation of a database named with the value of the env var POSTGRES_DB
    const pg = installCNHelmChart(xns, name, 'cn-postgres', {
      postgresPassword: this.password,
      db: {
        volumeSize: clusterLargeDisk ? '480Gi' : '240Gi',
      },
    });
    this.pg = pg;

    this.registerOutputs({
      address: pg.id.apply(() => `${name}.${xns.logicalName}.svc.cluster.local`),
    });
  }

  createDatabase(name: string): pulumi.Resource {
    // the DB is not immediately available even if you can connect to the pod, so psql might fail a few times at the beginning.
    const waitForPostgresToBeUp = new command.local.Command(
      `waitdb-${name}`,
      {
        create:
          `kubectl exec -n ${this.namespace.logicalName} postgres-0 -- ` +
          `bash -c "let RETRIES=60; until psql --username=cnadmin --dbname=\${POSTGRES_DB:-cantonnet} -c \\"select 1\\" > /dev/null 2>&1 || [ $RETRIES -eq 0 ]; do let RETRIES=RETRIES-1; sleep 1; done"`,
      },
      { dependsOn: this.pg }
    );
    return new command.local.Command(
      `createdb-${name}`,
      {
        create: `kubectl exec -n ${this.namespace.logicalName} postgres-0 -- psql --username=cnadmin --dbname=\${POSTGRES_DB:-cantonnet} -c "create database ${name}"`,
      },
      { dependsOn: waitForPostgresToBeUp }
    );
  }
}

// toplevel

export function installPostgres(xns: ExactNamespace, name: string): Postgres {
  let ret: Postgres;
  if (enableCloudSql) {
    ret = new CloudPostgres(xns, name);
  } else {
    ret = new CNPostgres(xns, name);
  }
  installCNHelmChart(
    xns,
    'postgres-metrics',
    'cn-postgres-metrics',
    {
      postgres: {
        hostname: ret.address,
        password: ret.password,
      },
    },
    [ret]
  );
  return ret;
}
