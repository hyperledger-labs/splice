import * as postgres from "./postgres";
import * as pulumi from "@pulumi/pulumi";
import * as k8s from "@pulumi/kubernetes";
import * as gcp from "@pulumi/gcp";

import * as _ from "lodash";
import * as fs from "fs";
import { PathLike } from "fs";

import { load } from "js-yaml";

const postgres_validator1_ip = postgres.createDatabase("validator1");

const GLOBAL_TIMEOUT_SEC = 300;

const config = new pulumi.Config();

const CLUSTER_BASENAME = config.require("CLUSTER_BASENAME");
const CLUSTER_NAME = `cn-${CLUSTER_BASENAME}net`;

// retrieve existing cluster IP, not managed with Pulumi yet
export const clusterIp = gcp.compute.getAddress({
  name: CLUSTER_NAME + "-ip",
});

// There are a few instances where this pulls data from the outside
// world. To avoid fully declaring these external data types, these are
// modeled as 'any', with the any warning disabled.

function loadYamlFromFile(path: PathLike): any {
  // eslint-disable-line @typescript-eslint/no-explicit-any
  return load(fs.readFileSync(path, "utf-8"));
}

function loadJsonFromFile(path: PathLike): any {
  // eslint-disable-line @typescript-eslint/no-explicit-any
  return JSON.parse(fs.readFileSync(path, "utf-8"));
}

const nodes = ["sv-1", "sv-2", "sv-3", "sv-4"];

const appToClientId = {
  "wallet": "KChYVPmxvHHUebLDXnNo3Z125WrxJiLb",
  "validator": "cf0cZaTagQUN59C1HBL2udiIBdFh2CWq",
  "sv-1": "OBpJ9oTyOLuAKF0H2hhzdSFUICt0diIn",
  "sv-2": "rv4bllgKWAiW9tBtdvURMdHW42MAXghz",
  "sv-3": "SeG68w0ubtLQ1dEMDOs4YKPRTyMMdDLk",
  "sv-4": "CqKgSbH54dqBT7V1JbnCxb6TfMN8I1cN",
} as { [key: string]: string };

const WELL_KNOWN_URL =
  "https://canton-network-dev.us.auth0.com/.well-known/openid-configuration";

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
        secrets.set(app["client_id"], {
          url: WELL_KNOWN_URL,
          "client-id": app["client_id"],
          "client-secret": app["client_secret"],
          "ledger-api-user": app["client_id"] + "@clients",
        });
      });

      return secrets;
    });
}

function cnChartValues(chartPath: string, overrideValues: any = {}): any {
  const chartDefaultValues = loadYamlFromFile(
    process.env.REPO_ROOT + "/cluster/helm/" + chartPath + "/values.yaml"
  );

  return _.merge(
    chartDefaultValues,
    {
      cluster: {
        basename: CLUSTER_BASENAME,
        name: CLUSTER_NAME,
        imageTag: config.require("IMAGE_TAG"),
        ipAddress: clusterIp.then(addr => addr.address),
        dnsName: config.require("CLUSTER_DNS_NAME"),
      },
    },
    overrideValues
  );
}

const auth0Secrets = getAuth0();

function installAuth0Secret(
  namespace: k8s.core.v1.Namespace,
  secretApp: string,
  nodename: string
): k8s.core.v1.Secret {
  return new k8s.core.v1.Secret(
    "auth0-secret-" + nodename,
    {
      metadata: {
        name: "cn-app-" + secretApp + "-ledger-api-auth",
        namespace: namespace.metadata.name,
      },
      stringData: auth0Secrets.then(
        (all: Auth0SecretMap) => all.get(appToClientId[nodename]) || {}
      ),
    },
    {
      dependsOn: namespace,
    }
  );
}

function installSvNodes() {
  nodes.forEach((nodename: string) => {
    const namespace = new k8s.core.v1.Namespace(nodename, {
      metadata: {
        name: nodename,
      },
    });

    const auth0Secret = installAuth0Secret(namespace, "sv", nodename);

    new k8s.helm.v3.Release(
      nodename,
      {
        name: "sv-app",
        namespace: namespace.metadata.name,
        chart: process.env.REPO_ROOT + "/cluster/helm/cn-sv-node/",
        values: cnChartValues("cn-sv-node", {
          bootstrapType:
            nodename === "sv-1" ? "found-consortium" : "join-via-svc-app",
        }),
        timeout: GLOBAL_TIMEOUT_SEC,
      },
      {
        dependsOn: [namespace, auth0Secret],
      }
    );
  });
}

function installDocs() {
  const namespace = new k8s.core.v1.Namespace("docs", {
    metadata: {
      name: "docs",
    },
  });
  const namespaceName = namespace.metadata.name;

  new k8s.helm.v3.Release(
    "docs",
    {
      name: "docs",
      namespace: namespaceName,
      chart: process.env.REPO_ROOT + "/cluster/helm/cn-docs/",
      values: cnChartValues("cn-docs"),
      timeout: GLOBAL_TIMEOUT_SEC,
    },
    {
      dependsOn: [namespace],
    }
  );
}

function installValidator() {
  const namespace = new k8s.core.v1.Namespace("validator", {
    metadata: {
      name: "validator",
    },
  });

  const auth0ValidatorSecret = installAuth0Secret(
    namespace,
    "validator",
    "validator"
  );
  const auth0WalletSecret = installAuth0Secret(namespace, "wallet", "wallet");

  new k8s.helm.v3.Release(
    "validator",
    {
      name: "validator",
      namespace: namespace.metadata.name,
      chart: process.env.REPO_ROOT + "/cluster/helm/cn-validator/",
      values: cnChartValues("cn-validator", {
        postgres: postgres_validator1_ip,
      }),
      timeout: GLOBAL_TIMEOUT_SEC,
    },
    {
      dependsOn: [auth0ValidatorSecret, auth0WalletSecret, namespace],
    }
  );
}

function installCertManager(): k8s.helm.v3.Release {
    const namespace = new k8s.core.v1.Namespace("cert-manager", {
        metadata: {
            name: "cert-manager"
        }
    });

    return new k8s.helm.v3.Release(
        "cert-manager",
        {
            name: "cert-manager",
            namespace: namespace.metadata.name,
            chart: "cert-manager",
            version: "1.11.0",
            repositoryOpts: {
                repo: "https://charts.jetstack.io",
            },
            timeout: GLOBAL_TIMEOUT_SEC,
        },
        {
            dependsOn: namespace
        }
    );
}

function installClusterIngress() {
    const certManager = installCertManager();

    const namespace = new k8s.core.v1.Namespace("cluster-ingress", {
        metadata: {
            name: "cluster-ingress"
        }
    });

    const dnsSaKey = new k8s.core.v1.Secret(
        "clouddns-dns01-solver-svc-acct",
        {
            metadata: {
                name: "clouddns-dns01-solver-svc-acct",
                namespace: namespace.metadata.name,
            },
            type: "Opaque",
            data: {
                "key.json": config.require("DNS_SA_KEY")
            }
        },
        {
            dependsOn: namespace
        });

    new k8s.helm.v3.Release(
        "cluster-ingress",
        {
            name: "cluster-ingress",
            namespace: namespace.metadata.name,
            chart: process.env.REPO_ROOT + "/cluster/helm/cn-cluster-ingress/",
            values: cnChartValues("cn-cluster-ingress"),
            timeout: GLOBAL_TIMEOUT_SEC,
        },
        {
            dependsOn: [ certManager, namespace, dnsSaKey ]
        }
    );
}

installClusterIngress()
installDocs();

// installSvNodes();
// installValidator();
