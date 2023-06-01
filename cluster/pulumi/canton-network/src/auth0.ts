import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import { KubeConfig, CoreV1Api } from '@kubernetes/client-node';
import { AuthenticationClient, ManagementClient } from 'auth0';

import type {
  Auth0Client,
  Auth0SecretMap,
  Auth0ClientAccessToken,
  Auth0ClientSecret,
  ClientIdMap,
} from './auth0types';
import { ExactNamespace, fixedTokens, requireEnv } from './utils';

type Auth0CacheMap = Record<string, Auth0ClientAccessToken>;

/* Access tokens deployed into a cluster need to have a lifetime at
 * least as long as the cluster is expected to run. This means that
 * cached tokens set to expire during the expected lifetime of an
 * environment need to be refreshed at deployment even if they aren't
 * quite expired.
 *
 * This constant sets the length of time tokens are expected to remain
 * valid. It is currently eight days, based on the seven day life of
 * TestNet and a short additional grace period.
 */
const REQUIRED_TOKEN_LIFETIME = 8 * 86400;

const AUTH0_FIXED_TOKEN_CACHE_NAME = 'auth0-fixed-token-cache';

function addTimeSeconds(t: Date, seconds: number): Date {
  const t2 = new Date(t);
  t2.setSeconds(t2.getSeconds() + seconds);
  return t2;
}

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
} as ClientIdMap;

const namespaceToUiClientId = {
  validator1: '5RJeTm41IwUs8VbbnZHxFEPjCX5ojfaK',
  splitwell: 'eeMLQ6qljnUcg9o1sJRbt4suCn2CYbSL',
  'sv-1': 'Ez65bly75dMqcKxQiJDF8rIP9xxkxV3J',
  'sv-2': 'G6Y5KYuiyOb0bnllGyQ2JKwjpwZM0Ai6',
  'sv-3': 'cgxHguMv32JLeew9S6wBDgNPHmPCIKaP',
  'sv-4': 'VoSuAamXhvwISHGgaCtULYmbRIWbQeTb',
} as ClientIdMap;

/// Auth0

const auth0Account = 'canton-network-dev.us';
const auth0Domain = `${auth0Account}.auth0.com`;

export class Auth0Fetch implements Auth0Client {
  private secrets: Auth0SecretMap | undefined;
  private auth0Cache: Auth0CacheMap | undefined;

  private k8sApi: CoreV1Api;

  constructor() {
    const kc = new KubeConfig();
    kc.loadFromDefault();

    this.k8sApi = kc.makeApiClient(CoreV1Api);
  }

  private async loadSecrets(): Promise<Auth0SecretMap> {
    const client = new ManagementClient({
      domain: auth0Domain,
      clientId: requireEnv('AUTH0_MANAGEMENT_API_CLIENT_ID'),
      clientSecret: requireEnv('AUTH0_MANAGEMENT_API_CLIENT_SECRET'),
      scope: 'read:clients read:client_keys',
    });

    const clients = await client.getClients();
    const secrets = new Map() as Auth0SecretMap;

    for (const client of clients) {
      if (client.client_id && client.client_secret) {
        secrets.set(client.client_id, client as Auth0ClientSecret);
      }
    }
    return secrets;
  }

  public async loadAuth0Cache(): Promise<void> {
    pulumi.log.info('Loading Auth0 Cache');
    const cacheMap = {} as Auth0CacheMap;

    try {
      const cacheSecret = await this.k8sApi.readNamespacedSecret(
        AUTH0_FIXED_TOKEN_CACHE_NAME,
        'default'
      );

      const { data } = cacheSecret.body;

      for (const clientId in data) {
        cacheMap[clientId] = JSON.parse(Buffer.from(data[clientId], 'base64').toString('ascii'));
      }

      pulumi.log.info('Auth0 cache loaded...');
    } catch (e) {
      pulumi.log.info('No Auth0 cache secret found.');
    }

    this.auth0Cache = cacheMap;
  }

  async saveAuth0Cache(): Promise<void> {
    pulumi.log.info('Saving Auth0 cache');
    const data = {} as Record<string, string>;

    if (!this.auth0Cache) {
      console.error('No auth0 cache loaded in Auth0Fetch');
      process.exit(1);
    }

    for (const clientId in this.auth0Cache) {
      const cachedToken = this.auth0Cache[clientId];

      data[clientId] = Buffer.from(JSON.stringify(cachedToken)).toString('base64');
    }

    try {
      await this.k8sApi.createNamespacedSecret('default', {
        apiVersion: 'v1',
        kind: 'Secret',
        metadata: {
          name: AUTH0_FIXED_TOKEN_CACHE_NAME,
        },
        data,
      });
    } catch (_) {
      try {
        console.log('Deleting existing secret');
        await this.k8sApi.deleteNamespacedSecret(AUTH0_FIXED_TOKEN_CACHE_NAME, 'default');

        console.log('Creating new secret');
        await this.k8sApi.createNamespacedSecret('default', {
          apiVersion: 'v1',
          kind: 'Secret',
          metadata: {
            name: AUTH0_FIXED_TOKEN_CACHE_NAME,
          },
          data,
        });
      } catch (e) {
        console.log('Auth0 cache update failed:', e);
        process.exit(1);
      }
    }

    pulumi.log.info('Auth0 cache saved');
  }

  public async getSecrets(): Promise<Auth0SecretMap> {
    if (this.secrets === undefined) {
      this.secrets = await this.loadSecrets();
    }
    return this.secrets;
  }

  public async getClientAccessToken(clientId: string, clientSecret: string): Promise<string> {
    pulumi.log.info('Getting access token for Auth0 client: ' + clientId);

    const now = new Date();

    if (this.auth0Cache) {
      const cachedSecret = this.auth0Cache[clientId];
      if (cachedSecret) {
        const cachedSecretExpiry = new Date(cachedSecret.expiry);
        if (addTimeSeconds(now, REQUIRED_TOKEN_LIFETIME) > cachedSecretExpiry) {
          pulumi.log.info('Ignoring expired cached Auth0 token for client: ' + clientId);
        } else {
          pulumi.log.info('Using cached Auth0 token for client: ' + clientId);
          return cachedSecret.accessToken;
        }
      }
    }

    pulumi.log.info('Querying access token for Auth0 client: ' + clientId);
    const auth0 = new AuthenticationClient({
      domain: `${auth0Account}.auth0.com`,
      clientId: clientId,
      clientSecret: clientSecret,
    });
    const tokenResponse = await auth0.clientCredentialsGrant({
      audience: 'https://canton.network.global',
    });

    const { expires_in } = tokenResponse;

    if (expires_in < REQUIRED_TOKEN_LIFETIME) {
      /* If you see this error, you either need to decrease the required token
       * lifetime or extend the length of the tokens issued by Auth0.
       */
      console.error(
        'Auth0 access token issued with expiry too short to meet REQUIRED_TOKEN_LIFETIME'
      );
      process.exit(1);
    }

    const expiry = addTimeSeconds(now, expires_in);

    if (this.auth0Cache && tokenResponse.access_token) {
      pulumi.log.info(
        'Caching access token for Auth0 client: ' + clientId + ' expiry: ' + expiry.toJSON()
      );

      this.auth0Cache[clientId] = {
        accessToken: tokenResponse.access_token,
        expiry: expiry.toJSON(),
      };
    }

    return tokenResponse.access_token;
  }
}

function lookupClientSecrets(
  allSecrets: Auth0SecretMap,
  clientIdMap: ClientIdMap,
  clientName: string
): Auth0ClientSecret {
  const appClientId = clientIdMap[clientName];

  if (!appClientId) {
    throw new Error(`Unknown Auth0 client ID for client: ${clientName}`);
  }

  const clientSecret = allSecrets.get(appClientId);

  if (!clientSecret) {
    throw new Error(`Client unknown to Auth0: ${clientName} (Client ID: ${appClientId})`);
  }

  /* This should never happen, allSecrets contains elements stored with their
   * client_id as the key. */
  if (clientSecret.client_id !== appClientId) {
    throw new Error(
      `client_id in secret map does not match expected value: ${clientSecret.client_id} !== ${appClientId}`
    );
  }

  return clientSecret;
}

async function auth0Secret(
  auth0Client: Auth0Client,
  allSecrets: Auth0SecretMap,
  clientName: string
): Promise<{ [key: string]: string }> {
  const clientSecrets = lookupClientSecrets(allSecrets, appToClientId, clientName);

  const clientId = clientSecrets.client_id;
  const clientSecret = clientSecrets.client_secret;

  if (fixedTokens()) {
    const accessToken = await auth0Client.getClientAccessToken(clientId, clientSecret);
    return {
      token: accessToken,
      'ledger-api-user': clientId + '@clients',
    };
  } else {
    return {
      url: `https://${auth0Domain}/.well-known/openid-configuration`,
      'client-id': clientId,
      'client-secret': clientSecret,
      'ledger-api-user': clientId + '@clients',
    };
  }
}

export async function installAuth0Secret(
  auth0Client: Auth0Client,
  xns: ExactNamespace,
  secretNameApp: string,
  clientName: string
): Promise<k8s.core.v1.Secret> {
  return new k8s.core.v1.Secret(
    'auth0-secret-' + xns.logicalName + '-' + clientName,
    {
      metadata: {
        name: 'cn-app-' + secretNameApp + '-ledger-api-auth',
        namespace: xns.ns.metadata.name,
      },
      stringData: await auth0Secret(auth0Client, await auth0Client.getSecrets(), clientName),
    },
    {
      dependsOn: xns.ns,
    }
  );
}

function auth0UISecret(allSecrets: Auth0SecretMap, clientName: string, namespaceName: string) {
  const clientSecrets = lookupClientSecrets(allSecrets, namespaceToUiClientId, namespaceName);

  return {
    url: `https://${auth0Domain}`,
    'client-id': clientSecrets.client_id,
  };
}

export async function installAuth0UISecret(
  auth0Client: Auth0Client,
  xns: ExactNamespace,
  secretNameApp: string,
  clientName: string
): Promise<k8s.core.v1.Secret> {
  return new k8s.core.v1.Secret(
    'auth0-ui-secret-' + xns.logicalName + '-' + clientName,
    {
      metadata: {
        name: 'cn-app-' + secretNameApp + '-ui-auth',
        namespace: xns.ns.metadata.name,
      },
      stringData: auth0UISecret(await auth0Client.getSecrets(), clientName, xns.logicalName),
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
