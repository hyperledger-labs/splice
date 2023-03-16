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
const CLUSTER_DNS_NAME = `${CLUSTER_BASENAME}.network.canton.global`;

// retrieve existing cluster IP, not managed with Pulumi yet
const clusterAddress = gcp.compute.getAddressOutput({
  name: CLUSTER_NAME + "-ip",
});

const clusterIp = pulumi.interpolate`${clusterAddress.address}`;

function configureDNS() {
  return [
    new gcp.dns.RecordSet(CLUSTER_DNS_NAME, {
      name: CLUSTER_DNS_NAME + ".",
      ttl: 60,
      type: "A",
      project: "da-gcp-canton-domain",
      managedZone: "canton-global",
      rrdatas: [clusterIp],
    }),

    new gcp.dns.RecordSet(CLUSTER_DNS_NAME + "-subdomains", {
      name: `*.${CLUSTER_DNS_NAME}.`,
      ttl: 60,
      type: "A",
      project: "da-gcp-canton-domain",
      managedZone: "canton-global",
      rrdatas: [clusterIp],
    })
  ];
}

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
  wallet: "KChYVPmxvHHUebLDXnNo3Z125WrxJiLb",
  validator: "cf0cZaTagQUN59C1HBL2udiIBdFh2CWq",
  "sv-1": "OBpJ9oTyOLuAKF0H2hhzdSFUICt0diIn",
  "sv-2": "rv4bllgKWAiW9tBtdvURMdHW42MAXghz",
  "sv-3": "SeG68w0ubtLQ1dEMDOs4YKPRTyMMdDLk",
  "sv-4": "CqKgSbH54dqBT7V1JbnCxb6TfMN8I1cN",
} as { [key: string]: string };

/// Auth0

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

const auth0Secrets = getAuth0();

function installAuth0Secret(
  ns: k8s.core.v1.Namespace,
  secretApp: string,
  nodename: string
): k8s.core.v1.Secret {
  return new k8s.core.v1.Secret(
    "auth0-secret-" + nodename,
    {
      metadata: {
        name: "cn-app-" + secretApp + "-ledger-api-auth",
        namespace: ns.metadata.name,
      },
      stringData: auth0Secrets.then(
        (all: Auth0SecretMap) => all.get(appToClientId[nodename]) || {}
      ),
    },
    {
      dependsOn: ns,
    }
  );
}

/// Chart Values

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
        ipAddress: clusterIp,
        dnsName: CLUSTER_DNS_NAME,
      },
    },
    overrideValues
  );
}

function exactNamespace(name: string): k8s.core.v1.Namespace {
  // Namespace with a fully specified name, exactly as it will
  // appear within Kubernetes. (No Pulumi suffix.)
  return new k8s.core.v1.Namespace(name, {
    metadata: {
      name,
    },
  });
}

function installSvNodes() {
  nodes.forEach((nodename: string) => {
    const ns = exactNamespace(nodename);

    const auth0Secret = installAuth0Secret(ns, "sv", nodename);

    new k8s.helm.v3.Release(
      nodename,
      {
        name: "sv-app",
        namespace: ns.metadata.name,
        chart: process.env.REPO_ROOT + "/cluster/helm/cn-sv-node/",
        values: cnChartValues("cn-sv-node", {
          bootstrapType:
            nodename === "sv-1" ? "found-consortium" : "join-via-svc-app",
        }),
        timeout: GLOBAL_TIMEOUT_SEC,
      },
      {
        dependsOn: [ns, auth0Secret],
      }
    );
  });
}

function installDocs() {
  const ns = exactNamespace("docs");

  const nsName = ns.metadata.name;

  new k8s.helm.v3.Release(
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

function installPostgres(
  ns: k8s.core.v1.Namespace,
  name: string
): k8s.helm.v3.Release {
  return new k8s.helm.v3.Release(
    name,
    {
      name: name,
      namespace: ns.metadata.name,
      chart: process.env.REPO_ROOT + "/cluster/helm/cn-postgres/",
      values: cnChartValues("cn-postgres"),
      timeout: GLOBAL_TIMEOUT_SEC,
    },
    {
      dependsOn: ns,
    }
  );
}

function installValidator() {
  const ns = exactNamespace("validator");

  const postgres = installPostgres(ns, "postgres");

  const auth0ValidatorSecret = installAuth0Secret(ns, "validator", "validator");
  const auth0WalletSecret = installAuth0Secret(ns, "wallet", "wallet");

  new k8s.helm.v3.Release(
    "validator",
    {
      name: "validator",
      namespace: ns.metadata.name,
      chart: process.env.REPO_ROOT + "/cluster/helm/cn-validator/",
      values: cnChartValues("cn-validator", {
        postgres: postgres_validator1_ip,
      }),
      timeout: GLOBAL_TIMEOUT_SEC,
    },
    {
      dependsOn: [ns, postgres, auth0ValidatorSecret, auth0WalletSecret],
    }
  );
}

function installCertManager(): k8s.helm.v3.Release {
  const ns = exactNamespace("cert-manager");

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

function installClusterIngress() {
  const certManager = installCertManager();

  const ns = exactNamespace("cluster-ingress");

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
      dependsOn: [certManager, ns, dnsSaKey],
    }
  );
}

//configureDNS();
//installClusterIngress()
//installDocs();
//installSvNodes();

installValidator();
