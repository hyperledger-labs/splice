import * as k8s from '@pulumi/kubernetes';
import { AuthenticationClient, Client, ManagementClient } from 'auth0';

import { ExactNamespace, fixedTokens } from './utils';

const appToClientId = {
  validator: 'cf0cZaTagQUN59C1HBL2udiIBdFh2CWq',
  svc: 'XJbLZ0uz6iceI4sHeQlIleuFZeCjczjC',
  scan: 'nDgBS0c1gPwbzF1v07CpyVw5yahYC9c6',
  directory: 'PRmBKfOZNmInZKg0qyIWn66RCSe9UBPs',
  splitwell: 'ekPlYxilradhEnpWdS80WfW63z1nHvKy',
  splitwell_validator: 'hqpZ6TP0wGyG2yYwhH6NLpuo0MpJMQZW',
  'sv-1': 'OBpJ9oTyOLuAKF0H2hhzdSFUICt0diIn',
  'sv-2': 'rv4bllgKWAiW9tBtdvURMdHW42MAXghz',
  'sv-3': 'SeG68w0ubtLQ1dEMDOs4YKPRTyMMdDLk',
  'sv-4': 'CqKgSbH54dqBT7V1JbnCxb6TfMN8I1cN',
  sv1_validator: '7YEiu1ty0N6uWAjL8tCAWTNi7phr7tov',
  sv2_validator: '5N2kwYLOqrHtnnikBqw8A7foa01kui7h',
  sv3_validator: 'V0RjcwPCsIXqYTslkF5mjcJn70AiD0dh',
  sv4_validator: 'FqRozyrmu2d6dFQYC4J9uK8Y6SXCVrhL',
} as { [key: string]: string };

const namespaceToUiClientId = {
  validator1: '5RJeTm41IwUs8VbbnZHxFEPjCX5ojfaK',
  splitwell: 'eeMLQ6qljnUcg9o1sJRbt4suCn2CYbSL',
  'sv-1': 'Ez65bly75dMqcKxQiJDF8rIP9xxkxV3J',
  'sv-2': 'G6Y5KYuiyOb0bnllGyQ2JKwjpwZM0Ai6',
  'sv-3': 'cgxHguMv32JLeew9S6wBDgNPHmPCIKaP',
  'sv-4': 'VoSuAamXhvwISHGgaCtULYmbRIWbQeTb',
} as { [key: string]: string };

/// Auth0

const auth0Account = 'canton-network-dev.us';
const auth0Domain = `${auth0Account}.auth0.com`;

function requireEnv(name: string): string {
  const value = process.env[name];

  if (!value) {
    console.error(`Environment variable ${name} is undefined.`);
    process.exit(1);
  } else {
    return value;
  }
}

const clientId = requireEnv('AUTH0_MANAGEMENT_API_CLIENT_ID');
const clientSecret = requireEnv('AUTH0_MANAGEMENT_API_CLIENT_SECRET');

type Auth0SecretMap = Map<string, Client>;

async function getAuth0(): Promise<Auth0SecretMap> {
  const client = new ManagementClient({
    domain: auth0Domain,
    clientId: clientId,
    clientSecret: clientSecret,
    scope: 'read:clients read:client_keys',
  });

  const clients = await client.getClients();

  const secrets = new Map() as Auth0SecretMap;

  for (const client of clients) {
    if (client.client_id && client.client_secret) {
      secrets.set(client.client_id, client);
    }
  }
  return secrets;
}

const auth0Secrets = getAuth0();

async function auth0Secret(
  allSecrets: Auth0SecretMap,
  clientName: string
): Promise<{ [key: string]: string }> {
  const clientSecrets = allSecrets.get(appToClientId[clientName]) || {};

  const clientId = clientSecrets.client_id || '';
  const clientSecret = clientSecrets.client_secret || '';

  if (fixedTokens()) {
    const auth0 = new AuthenticationClient({
      domain: `${auth0Account}.auth0.com`,
      clientId: clientId,
      clientSecret: clientSecret,
    });
    const accessToken = await auth0.clientCredentialsGrant({
      audience: 'https://canton.network.global',
    });
    return {
      token: accessToken.access_token,
      'ledger-api-user': clientId + '@clients',
    };
  } else {
    return Promise.resolve({
      url: `https://${auth0Domain}/.well-known/openid-configuration`,
      'client-id': clientId,
      'client-secret': clientSecret,
      'ledger-api-user': clientId + '@clients',
    });
  }
}

export function installAuth0Secret(
  xns: ExactNamespace,
  secretNameApp: string,
  clientName: string
): k8s.core.v1.Secret {
  return new k8s.core.v1.Secret(
    'auth0-secret-' + xns.logicalName + '-' + clientName,
    {
      metadata: {
        name: 'cn-app-' + secretNameApp + '-ledger-api-auth',
        namespace: xns.ns.metadata.name,
      },
      stringData: auth0Secrets.then((all: Auth0SecretMap) => auth0Secret(all, clientName)),
    },
    {
      dependsOn: xns.ns,
    }
  );
}

function auth0UISecret(allSecrets: Auth0SecretMap, clientName: string, namespaceName: string) {
  const clientSecrets = allSecrets.get(namespaceToUiClientId[namespaceName]) || {};

  const clientId = clientSecrets.client_id || '';

  return {
    url: `https://${auth0Domain}`,
    'client-id': clientId,
  };
}

export function installAuth0UISecret(
  xns: ExactNamespace,
  secretNameApp: string,
  clientName: string
): k8s.core.v1.Secret {
  return new k8s.core.v1.Secret(
    'auth0-ui-secret-' + xns.logicalName + '-' + clientName,
    {
      metadata: {
        name: 'cn-app-' + secretNameApp + '-ui-auth',
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
        key: 'ledger-api-user',
        name: `cn-app-${secretName.toLowerCase().replaceAll('_', '-')}-ledger-api-auth`,
        optional: false,
      },
    },
  };
}
