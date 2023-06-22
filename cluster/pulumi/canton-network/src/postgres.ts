import * as gcp from '@pulumi/gcp';
import * as pulumi from '@pulumi/pulumi';
import { ExactNamespace, installCNHelmChart } from 'cn-pulumi-common';

const project = gcp.organizations.getProjectOutput({});

// use existing default network (needs to have a private vpc connection)
const privateNetwork = gcp.compute.Network.get(
  'default',
  pulumi.interpolate`https://www.googleapis.com/compute/v1/projects/${project.name}/global/networks/default`
);

function installCloudPostgres(xns: ExactNamespace, name: string): pulumi.Output<string> {
  const logicalName = xns.logicalName + '-' + name;
  const pgSvc = new gcp.sql.DatabaseInstance(logicalName, {
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
  });

  new gcp.sql.Database(
    `db-${logicalName}`,
    {
      instance: pgSvc.name,
      name: 'cantonnet',
    },
    {
      dependsOn: pgSvc,
    }
  );

  new gcp.sql.User(
    `user-${logicalName}`,
    {
      instance: pgSvc.name,
      name: 'cnadmin',
      password: 'cnadmin',
    },
    {
      dependsOn: pgSvc,
    }
  );

  return pgSvc.privateIpAddress;
}

/// Database

function installCNPostgres(xns: ExactNamespace, name: string): pulumi.Output<string> {
  const pg = installCNHelmChart(xns, name, 'cn-postgres');

  return pg.id.apply(() => `${name}.${xns.logicalName}.svc.cluster.local`);
}

// toplevel

const ENABLE_CLOUD_SQL = 'true' === (process.env.ENABLE_CLOUD_SQL ?? 'false');

export function installPostgres(xns: ExactNamespace, name: string): pulumi.Output<string> {
  if (ENABLE_CLOUD_SQL) {
    return installCloudPostgres(xns, name);
  } else {
    return installCNPostgres(xns, name);
  }
}
