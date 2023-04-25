import * as k8s from "@pulumi/kubernetes";

import fetch from "node-fetch";

import { ExactNamespace, fixedTokens } from "./utils";

const appToClientId = {
  validator: "cf0cZaTagQUN59C1HBL2udiIBdFh2CWq",
  svc: "XJbLZ0uz6iceI4sHeQlIleuFZeCjczjC",
  scan: "nDgBS0c1gPwbzF1v07CpyVw5yahYC9c6",
  directory: "PRmBKfOZNmInZKg0qyIWn66RCSe9UBPs",
  splitwell: "ekPlYxilradhEnpWdS80WfW63z1nHvKy",
  splitwell_validator: "hqpZ6TP0wGyG2yYwhH6NLpuo0MpJMQZW",
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
      if (response.ok) {
        return response.json();
      }

      console.error(
        "Error fetching secrets from Auth0: " + response.statusText
      );
      process.exit(1);
    })
    .then((data: [{ client_id: string }]) => {
      const secrets = new Map() as Auth0SecretMap;

      data.forEach((app: { client_id: string }) => {
        secrets.set(app["client_id"], app);
      });

      return secrets;
    });
}

const auth0Secrets = getAuth0();

function getClientToken(
  clientId: string,
  clientSecret: string
): Promise<string> {
  return fetch("https://canton-network-dev.us.auth0.com/oauth/token", {
    method: "POST",
    headers: {
      "content-type": "application/json",
    },
    body: JSON.stringify({
      client_id: clientId,
      client_secret: clientSecret,
      audience: "https://canton.network.global",
      grant_type: "client_credentials",
    }),
  })
    .then((response) => {
      if (response.ok) {
        return response.json();
      }
      console.error("Error fetching clientToken: " + response.statusText);
      process.exit(1);
    })
    .then((data: { access_token: string }) => data.access_token);
}

function auth0Secret(
  allSecrets: Auth0SecretMap,
  clientName: string
): Promise<{ [key: string]: string }> {
  const clientSecrets = allSecrets.get(appToClientId[clientName]) || {};

  const clientId = clientSecrets["client_id"] || "";
  const clientSecret = clientSecrets["client_secret"] || "";

  if (fixedTokens()) {
    return getClientToken(clientId, clientSecret).then((accessToken) => {
      return {
        token: accessToken,
        "ledger-api-user": clientId + "@clients",
      };
    });
  } else {
    return Promise.resolve({
      url: "https://canton-network-dev.us.auth0.com/.well-known/openid-configuration",
      "client-id": clientId,
      "client-secret": clientSecret,
      "ledger-api-user": clientId + "@clients",
    });
  }
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

export function auth0UserNameEnvVar(
  name: string,
  secretName: string | null = null
): k8s.types.input.core.v1.EnvVar {
  if (!secretName) {
    secretName = name;
  }

  return {
    name: `CN_APP_${name.toUpperCase()}_LEDGER_API_AUTH_USER_NAME`,
    valueFrom: {
      secretKeyRef: {
        key: "ledger-api-user",
        name: `cn-app-${secretName
          .toLowerCase()
          .replaceAll("_", "-")}-ledger-api-auth`,
        optional: false,
      },
    },
  };
}
