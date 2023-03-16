import * as pulumi from "@pulumi/pulumi";
import * as k8s from "@pulumi/kubernetes";
import * as gcp from "@pulumi/gcp";

import { ExactNamespace, GLOBAL_TIMEOUT_SEC, cnChartValues } from "./utils";

const project = gcp.organizations.getProjectOutput({});

// use existing default network (needs to have a private vpc connection)
const privateNetwork = gcp.compute.Network.get(
  "default",
  pulumi.interpolate`https://www.googleapis.com/compute/v1/projects/${project.name}/global/networks/default`
);

export function installCloudPostgres(
  xns: ExactNamespace,
  name: String
): pulumi.Output<String> {
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

/// Database

export function installCNPostgres(
  xns: ExactNamespace,
  name: string
): pulumi.Output<string> {
  const pg = new k8s.helm.v3.Release(
    xns.logicalName + "-" + name,
    {
      name: name,
      namespace: xns.ns.metadata.name,
      chart: process.env.REPO_ROOT + "/cluster/helm/cn-postgres/",
      values: cnChartValues("cn-postgres"),
      timeout: GLOBAL_TIMEOUT_SEC,
    },
    {
      dependsOn: xns.ns,
    }
  );

  return pg.id.apply((_) => `${name}.${xns.logicalName}.svc.cluster.local`);
}
