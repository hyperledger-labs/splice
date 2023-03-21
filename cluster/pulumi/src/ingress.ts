import * as k8s from "@pulumi/kubernetes";

import { config, exactNamespace, GLOBAL_TIMEOUT_SEC, installCNHelmChart } from "./utils";

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

export function installClusterIngress(
  validator: k8s.helm.v3.Release,
  splitwell: k8s.helm.v3.Release,
  docs: k8s.helm.v3.Release
) {
  const certManager = installCertManager();

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
