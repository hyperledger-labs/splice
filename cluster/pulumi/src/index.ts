import * as pulumi from "@pulumi/pulumi";
import * as k8s from "@pulumi/kubernetes";
import * as gcp from "@pulumi/gcp";

import * as postgres from "./postgres";

import {
  config,
  clusterIp,
  cnChartValues,
  ExactNamespace,
  exactNamespace,
  installCNHelmChart,
  GLOBAL_TIMEOUT_SEC,
  CLUSTER_BASENAME,
  CLUSTER_NAME,
} from "./utils";

import { installAuth0Secret, installAuth0UISecret } from "./auth0";

/// Toplevel Chart Installs

function installDomain(
  xns: ExactNamespace,
  name: string,
  postgresDb: pulumi.Output<string>
): k8s.helm.v3.Release {
  return installCNHelmChart(
    xns,
    "domain-" + xns.logicalName + "-" + name,
    "cn-domain",
    {
      postgres: postgresDb,
      domainServiceName: name,
    }
  );
}

function installSVC(): k8s.helm.v3.Release {
  const xns = exactNamespace("svc");

  const postgresDb = postgres.installPostgres(xns, "postgres");

  const domain = installDomain(xns, "global-domain", postgresDb);

  const dependsOn = [
    xns.ns,
    installAuth0Secret(xns, "sv1", "sv-1"),
    installAuth0Secret(xns, "sv2", "sv-2"),
    installAuth0Secret(xns, "sv3", "sv-3"),
    installAuth0Secret(xns, "sv4", "sv-4"),
    installAuth0Secret(xns, "scan", "scan"),
    installAuth0Secret(xns, "directory", "directory"),
    installAuth0Secret(xns, "svc", "svc"),
  ];

  return installCNHelmChart(
    xns,
    "svc",
    "cn-svc",
    {
      postgres: postgresDb,
    },
    dependsOn
  );
}

function installSvNode(svc: k8s.helm.v3.Release, nodename: string) {
  const xns = exactNamespace(nodename);

  const auth0Secret = installAuth0Secret(xns, "sv", nodename);

  const dependsOn = [svc, xns.ns, auth0Secret];

  installCNHelmChart(
    xns,
    nodename + "-sv-app",
    "cn-sv-node",
    {
      bootstrapType:
        nodename === "sv-1" ? "found-consortium" : "join-via-svc-app",
    },
    dependsOn
  );
}

function installValidator(
  svc: k8s.helm.v3.Release,
  name: string
): k8s.helm.v3.Release {
  const xns = exactNamespace(name);

  const postgresDb = postgres.installPostgres(xns, "postgres");

  const dependsOn = [
    svc,
    xns.ns,
    installAuth0Secret(xns, "validator", "validator"),
    installAuth0Secret(xns, "wallet", "wallet"),

    installAuth0UISecret(xns, "directory", "directory"),
    installAuth0UISecret(xns, "splitwell", "splitwell"),
    installAuth0UISecret(xns, "wallet", "wallet"),
  ];

  return installCNHelmChart(
    xns,
    "validator-" + xns.logicalName,
    "cn-validator",
    {
      postgres: postgresDb,
    },
    dependsOn
  );
}

function installSplitwell(svc: k8s.helm.v3.Release): k8s.helm.v3.Release {
  const xns = exactNamespace("splitwell");

  const postgresDb = postgres.installPostgres(xns, "postgres");

  const domain = installDomain(xns, "domain", postgresDb);

  const dependsOn = [
    xns.ns,
    svc,
    installAuth0Secret(xns, "splitwell", "splitwell"),
    installAuth0Secret(xns, "validator", "splitwell_validator"),
    installAuth0Secret(xns, "wallet", "splitwell_wallet"),
    installAuth0UISecret(xns, "wallet", "splitwell"),
  ];

  return installCNHelmChart(
    xns,
    "splitwell",
    "cn-splitwell",
    {
      postgres: postgresDb,
    },
    dependsOn
  );
}

function installDocs(): k8s.helm.v3.Release {
  const xns = exactNamespace("docs");

  const nsName = xns.ns.metadata.name;

  const dependsOn = [xns.ns];

  return installCNHelmChart(xns, "docs", "cn-docs", {}, dependsOn);
}

function installCertManager(): k8s.helm.v3.Release {
  const { ns } = exactNamespace("cert-manager");

  return new k8s.helm.v3.Release(
    "cert-manager",
    {
      name: "cert-manager",
      namespace: ns.metadata.name,
      chart: "cert-manager",
      version: "1.11.0",
      repositoryOpts: {
        repo: "https://charts.jetstack.io",
      },
      timeout: GLOBAL_TIMEOUT_SEC,
    },
    {
      dependsOn: ns,
    }
  );
}

function installClusterIngress(
  certManager: k8s.helm.v3.Release,
  validator: k8s.helm.v3.Release,
  splitwell: k8s.helm.v3.Release,
  docs: k8s.helm.v3.Release
) {
  const xns = exactNamespace("cluster-ingress");

  const dnsSaKey = new k8s.core.v1.Secret(
    "clouddns-dns01-solver-svc-acct",
    {
      metadata: {
        name: "clouddns-dns01-solver-svc-acct",
        namespace: xns.ns.metadata.name,
      },
      type: "Opaque",
      data: {
        "key.json": config.require("DNS_SA_KEY"),
      },
    },
    {
      dependsOn: xns.ns,
    }
  );

  const dependsOn = [certManager, xns.ns, dnsSaKey, validator, splitwell, docs];

  installCNHelmChart(
    xns,
    "cluster-ingress",
    "cn-cluster-ingress",
    {},
    dependsOn
  );
}

function installCluster() {
  //configureDNS();
  //const certManager = installCertManager();

  const svc = installSVC();

  installSvNode(svc, "sv-1");
  installSvNode(svc, "sv-2");
  installSvNode(svc, "sv-3");
  installSvNode(svc, "sv-4");
  const validator = installValidator(svc, "validator1");
  const splitwell = installSplitwell(svc);

  //const docs = installDocs();
  //installClusterIngress(certManager, validator, splitwell, docs);
}

installCluster();
