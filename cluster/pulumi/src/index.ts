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
  GLOBAL_TIMEOUT_SEC,
  CLUSTER_BASENAME,
  CLUSTER_NAME,
} from "./utils";

const nodes = ["sv-1", "sv-2", "sv-3", "sv-4"];

const appToClientId = {
  wallet: "KChYVPmxvHHUebLDXnNo3Z125WrxJiLb",
  validator: "cf0cZaTagQUN59C1HBL2udiIBdFh2CWq",
  svc: "XJbLZ0uz6iceI4sHeQlIleuFZeCjczjC",
  scan: "nDgBS0c1gPwbzF1v07CpyVw5yahYC9c6",
  directory: "PRmBKfOZNmInZKg0qyIWn66RCSe9UBPs",
  splitwell: "ekPlYxilradhEnpWdS80WfW63z1nHvKy",
  splitwell_validator: "hqpZ6TP0wGyG2yYwhH6NLpuo0MpJMQZW",
  splitwell_wallet: "BMSnkJmJMGQqTQY8sabXNHVEzvNW5MMo",
  "sv-1": "OBpJ9oTyOLuAKF0H2hhzdSFUICt0diIn",
  "sv-2": "rv4bllgKWAiW9tBtdvURMdHW42MAXghz",
  "sv-3": "SeG68w0ubtLQ1dEMDOs4YKPRTyMMdDLk",
  "sv-4": "CqKgSbH54dqBT7V1JbnCxb6TfMN8I1cN",
} as { [key: string]: string };

const namespaceToUiClientId = {
  validator1: "5RJeTm41IwUs8VbbnZHxFEPjCX5ojfaK",
  splitwell: "eeMLQ6qljnUcg9o1sJRbt4suCn2CYbSL",
} as { [key: string]: string };

/// Auth0

type Auth0SecretMap = Map<string, { [key: string]: string }>;

function getAuth0(): Promise<Auth0SecretMap> {
  const auth0Token = process.env.AUTH0_MANAGEMENT_API_TOKEN;
  if (!auth0Token) {
    console.error(
      "Environment variable AUTH0_MANAGEMENT_API_TOKEN is undefined."
    );
    console.error(
      "   Please see https://github.com/DACH-NY/the-real-canton-coin/tree/main/cluster#auth0-secrets"
    );
    process.exit(1);
  }

  return fetch("https://canton-network-dev.us.auth0.com/api/v2/clients", {
    headers: {
      Authorization: "Bearer " + auth0Token,
    },
  })
    .then((response) => {
      if (!response.ok) {
        console.error("Error fetching secrets from Auth0.");
        process.exit(1);
      }

      return response.json();
    })
    .then((data) => {
      const secrets = new Map() as Auth0SecretMap;

      data.forEach((app: any) => {
        // eslint-disable-line @typescript-eslint/no-explicit-any
        secrets.set(app["client_id"], app);
      });

      return secrets;
    });
}

const auth0Secrets = getAuth0();

function auth0Secret(allSecrets: Auth0SecretMap, clientName: string) {
  const clientSecrets = allSecrets.get(appToClientId[clientName]) || {};

  const clientId = clientSecrets["client_id"] || "";
  const clientSecret = clientSecrets["client_secret"] || "";

  return {
    url: "https://canton-network-dev.us.auth0.com/.well-known/openid-configuration",
    "client-id": clientId,
    "client-secret": clientSecret,
    "ledger-api-user": clientId + "@clients",
  };
}

function installAuth0Secret(
  xns: ExactNamespace,
  secretNameApp: string,
  clientName: string
): k8s.core.v1.Secret {
  return new k8s.core.v1.Secret(
    "auth0-secret-" + xns.logicalName + "-" + clientName,
    {
      metadata: {
        name: "cn-app-" + secretNameApp + "-ledger-api-auth",
        namespace: xns.ns.metadata.name,
      },
      stringData: auth0Secrets.then((all: Auth0SecretMap) =>
        auth0Secret(all, clientName)
      ),
    },
    {
      dependsOn: xns.ns,
    }
  );
}

function auth0UISecret(
  allSecrets: Auth0SecretMap,
  clientName: string,
  namespaceName: string
) {
  const clientSecrets =
    allSecrets.get(namespaceToUiClientId[namespaceName]) || {};

  const clientId = clientSecrets["client_id"] || "";

  return {
    url: "https://canton-network-dev.us.auth0.com",
    "client-id": clientId,
  };
}

function installAuth0UISecret(
  xns: ExactNamespace,
  secretNameApp: string,
  clientName: string
): k8s.core.v1.Secret {
  return new k8s.core.v1.Secret(
    "auth0-ui-secret-" + xns.logicalName + "-" + clientName,
    {
      metadata: {
        name: "cn-app-" + secretNameApp + "-ui-auth",
        namespace: xns.ns.metadata.name,
      },
      stringData: auth0Secrets.then((all: Auth0SecretMap) =>
        auth0UISecret(all, clientName, xns.logicalName)
      ),
    },
    {
      dependsOn: xns.ns,
    }
  );
}

/// Toplevel Chart Installs

function installSVC(): k8s.helm.v3.Release {
  const xns = exactNamespace("svc");

  const postgresDb = postgres.installCNPostgres(xns, "postgres");

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

  return new k8s.helm.v3.Release(
    "svc",
    {
      name: "svc",
      namespace: xns.ns.metadata.name,
      chart: process.env.REPO_ROOT + "/cluster/helm/cn-svc/",
      values: cnChartValues("cn-svc"),
      timeout: GLOBAL_TIMEOUT_SEC,
    },
    {
      dependsOn,
    }
  );
}

function installSvNodes(svc: k8s.helm.v3.Release) {
  nodes.forEach((nodename: string) => {
    const xns = exactNamespace(nodename);

    const auth0Secret = installAuth0Secret(xns, "sv", nodename);

    new k8s.helm.v3.Release(
      nodename,
      {
        name: "sv-app",
        namespace: xns.ns.metadata.name,
        chart: process.env.REPO_ROOT + "/cluster/helm/cn-sv-node/",
        values: cnChartValues("cn-sv-node", {
          bootstrapType:
            nodename === "sv-1" ? "found-consortium" : "join-via-svc-app",
        }),
        timeout: GLOBAL_TIMEOUT_SEC,
      },
      {
        dependsOn: [svc, xns.ns, auth0Secret],
      }
    );
  });
}

function installValidator(
  svc: k8s.helm.v3.Release,
  name: string
): k8s.helm.v3.Release {
  const xns = exactNamespace(name);

  // const postgresDb = postgres.installCloudPostgres(xns, "validator1");

  const postgresDb = postgres.installCNPostgres(xns, "postgres");

  const dependsOn = [
    svc,
    xns.ns,
    installAuth0Secret(xns, "validator", "validator"),
    installAuth0Secret(xns, "wallet", "wallet"),

    installAuth0UISecret(xns, "directory", "directory"),
    installAuth0UISecret(xns, "splitwell", "splitwell"),
    installAuth0UISecret(xns, "wallet", "wallet"),
  ];

  return new k8s.helm.v3.Release(
    "validator-" + xns.logicalName,
    {
      name: name,
      namespace: xns.ns.metadata.name,
      chart: process.env.REPO_ROOT + "/cluster/helm/cn-validator/",
      values: cnChartValues("cn-validator", {
        postgres: postgresDb,
      }),
      timeout: GLOBAL_TIMEOUT_SEC,
    },
    {
      dependsOn,
    }
  );
}

function installSplitwell(svc: k8s.helm.v3.Release): k8s.helm.v3.Release {
  const xns = exactNamespace("splitwell");

  const postgresDb = postgres.installCNPostgres(xns, "postgres");

  const dependsOn = [
    xns.ns,
    svc,
    installAuth0Secret(xns, "splitwell", "splitwell"),
    installAuth0Secret(xns, "validator", "splitwell_validator"),
    installAuth0Secret(xns, "wallet", "splitwell_wallet"),
    installAuth0UISecret(xns, "wallet", "splitwell"),
  ];

  return new k8s.helm.v3.Release(
    "splitwell",
    {
      name: "splitwell",
      namespace: xns.ns.metadata.name,
      chart: process.env.REPO_ROOT + "/cluster/helm/cn-splitwell/",
      values: cnChartValues("cn-splitwell"),
      timeout: GLOBAL_TIMEOUT_SEC,
    },
    {
      dependsOn,
    }
  );
}

function installDocs(): k8s.helm.v3.Release {
  const { ns } = exactNamespace("docs");

  const nsName = ns.metadata.name;

  return new k8s.helm.v3.Release(
    "docs",
    {
      name: "docs",
      namespace: nsName,
      chart: process.env.REPO_ROOT + "/cluster/helm/cn-docs/",
      values: cnChartValues("cn-docs"),
      timeout: GLOBAL_TIMEOUT_SEC,
    },
    {
      dependsOn: [ns],
    }
  );
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
  const { ns } = exactNamespace("cluster-ingress");

  const dnsSaKey = new k8s.core.v1.Secret(
    "clouddns-dns01-solver-svc-acct",
    {
      metadata: {
        name: "clouddns-dns01-solver-svc-acct",
        namespace: ns.metadata.name,
      },
      type: "Opaque",
      data: {
        "key.json": config.require("DNS_SA_KEY"),
      },
    },
    {
      dependsOn: ns,
    }
  );

  new k8s.helm.v3.Release(
    "cluster-ingress",
    {
      name: "cluster-ingress",
      namespace: ns.metadata.name,
      chart: process.env.REPO_ROOT + "/cluster/helm/cn-cluster-ingress/",
      values: cnChartValues("cn-cluster-ingress"),
      timeout: GLOBAL_TIMEOUT_SEC,
    },
    {
      dependsOn: [certManager, ns, dnsSaKey, validator, splitwell, docs],
    }
  );
}

//configureDNS();
//const certManager = installCertManager();
const svc = installSVC();
installSvNodes(svc);
const validator = installValidator(svc, "validator1");
const splitwell = installSplitwell(svc);
const docs = installDocs();
//installClusterIngress(certManager, validator, splitwell, docs);
