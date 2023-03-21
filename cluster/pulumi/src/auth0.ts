import * as k8s from "@pulumi/kubernetes";

import fetch from "node-fetch";

import { ExactNamespace } from "./utils";

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
    .then((data: any) => {
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

export function installAuth0Secret(
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

export function installAuth0UISecret(
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
