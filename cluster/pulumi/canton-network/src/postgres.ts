import * as gcp from '@pulumi/gcp';
import * as pulumi from '@pulumi/pulumi';
import * as random from '@pulumi/random';
import { ExactNamespace, installCNHelmChart } from 'cn-pulumi-common';

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
        },
      },
      {
        parent: this,
      }
    );

    const pgDB = new gcp.sql.Database(
      `db-${logicalName}`,
      {
        instance: this.pgSvc.name,
        name: 'cantonnet',
      },
      {
        parent: this,
        deletedWith: this.pgSvc,
      }
    );

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
}

class CNPostgres extends pulumi.ComponentResource implements Postgres {
  name: string;
  namespace: ExactNamespace;
  address: pulumi.Output<string>;
  password: pulumi.Output<string>;

  constructor(xns: ExactNamespace, name: string) {
    const logicalName = xns.logicalName + '-' + name;
    super('canton:network:postgres', logicalName);

    this.name = name;
    this.namespace = xns;
    this.address = pulumi.output(`${this.name}.${this.namespace.logicalName}.svc.cluster.local`);
    this.password = generatePassword(`${logicalName}-passwd`, { parent: this }).result;

    const pg = installCNHelmChart(xns, name, 'cn-postgres', {
      postgresPassword: this.password,
    });

    this.registerOutputs({
      address: pg.id.apply(() => `${name}.${xns.logicalName}.svc.cluster.local`),
    });
  }
}

// toplevel

const ENABLE_CLOUD_SQL = 'true' === (process.env.ENABLE_CLOUD_SQL ?? 'false');

export function installPostgres(xns: ExactNamespace, name: string): Postgres {
  if (ENABLE_CLOUD_SQL) {
    return new CloudPostgres(xns, name);
  } else {
    return new CNPostgres(xns, name);
  }
}
