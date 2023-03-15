import * as pulumi from "@pulumi/pulumi";
import * as gcp from "@pulumi/gcp";

const project = gcp.organizations.getProjectOutput({});

// use existing default network (needs to have a private vpc connection)
const privateNetwork = gcp.compute.Network.get(
  "default",
  pulumi.interpolate`https://www.googleapis.com/compute/v1/projects/${project.name}/global/networks/default`
);

export function createDatabase(name: String): pulumi.Output<String> {
  const pgSvc = new gcp.sql.DatabaseInstance(`postgres-${name}`, {
    databaseVersion: "POSTGRES_14",
    deletionProtection: true,
    region: "us-central1",
    settings: {
      backupConfiguration: {
        enabled: true,
        pointInTimeRecoveryEnabled: true,
      },
      insightsConfig: {
        queryInsightsEnabled: true,
      },
      // tier is equivalent to "Standard" machine with 2 vCpus and 7.5GB RAM
      tier: "db-custom-2-7680",
      ipConfiguration: {
        ipv4Enabled: false,
        privateNetwork: privateNetwork.id,
        enablePrivatePathForGoogleCloudServices: true,
      },
    },
  });

  const db = new gcp.sql.Database(`cantonnet-${name}`, {
    instance: pgSvc.name,
    name: "cantonnet",
  });

  const users = new gcp.sql.User(`user-${name}`, {
    instance: pgSvc.name,
    name: "cnadmin",
    password: "cnadmin",
  });

  return pgSvc.privateIpAddress;
}
