import * as pulumi from "@pulumi/pulumi";
import * as k8s from "@pulumi/kubernetes";

import * as _ from "lodash";
import * as fs from "fs";
import { PathLike } from "fs";

import { load } from "js-yaml";

const GLOBAL_TIMEOUT_SEC = 300;

const config = new pulumi.Config();

// There are a few instances where this pulls data from the outside
// world. To avoid fully declaring these external data types, these are
// modeled as 'any', with the any warning disabled.

function loadYamlFromFile(path: PathLike): any {
  // eslint-disable-line @typescript-eslint/no-explicit-any
  return load(fs.readFileSync(path, "utf-8"));
}

const IMAGE_TAG = config.require("IMAGE_TAG");

const nodes = ["sv-1", "sv-2", "sv-3", "sv-4"];

const appToClientId = {
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

function chartValues(chartPath: string, overrides: any = {}): any {
  const CHART_DEFAULT_VALUES = loadYamlFromFile(
    process.env.REPO_ROOT + "/cluster/helm/" + chartPath + "/values.yaml"
  );

  return _.merge(
    CHART_DEFAULT_VALUES,
    {
      cluster: {
        imageTag: IMAGE_TAG,
      },
    },
    overrides
  );
}

const auth0Secrets = getAuth0();

function installSvNodes() {
  nodes.forEach((nodename: string) => {
    const namespace = new k8s.core.v1.Namespace(nodename, {
      metadata: {
        name: nodename,
      },
    });

    const namespaceName = namespace.metadata.name;

    const auth0Secret = new k8s.core.v1.Secret(
      "auth0-secret-" + nodename,
      {
        metadata: {
          name: "cn-app-sv-ledger-api-auth",
          namespace: namespaceName,
        },
        stringData: auth0Secrets.then(
          (all: Auth0SecretMap) => all.get(appToClientId[nodename]) || {}
        ),
      },
      {
        dependsOn: namespace,
      }
    );

    const bootstrapType =
      nodename === "sv-1" ? "found-consortium" : "join-via-svc-app";

    new k8s.helm.v3.Release(
      nodename,
      {
        name: "sv-app",
        namespace: namespaceName,
        chart: process.env.REPO_ROOT + "/cluster/helm/cn-sv-node/",
        values: chartValues("cn-sv-node", { bootstrapType }),
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
      values: chartValues("cn-docs"),
      timeout: GLOBAL_TIMEOUT_SEC,
    },
    {
      dependsOn: [namespace],
    }
  );
}

installSvNodes();
installDocs();
