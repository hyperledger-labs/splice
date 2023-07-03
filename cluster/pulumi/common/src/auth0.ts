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
  Auth0Config,
} from './auth0types';
import { ExactNamespace, fixedTokens } from './utils';

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

function addTimeSeconds(t: Date, seconds: number): Date {
  const t2 = new Date(t);
  t2.setSeconds(t2.getSeconds() + seconds);
  return t2;
}

export class Auth0Fetch implements Auth0Client {
  private secrets: Auth0SecretMap | undefined;
  private auth0Cache: Auth0CacheMap | undefined;

  private k8sApi: CoreV1Api;
  private cfg: Auth0Config;

  constructor(cfg: Auth0Config) {
    const kc = new KubeConfig();
    kc.loadFromDefault();

    this.k8sApi = kc.makeApiClient(CoreV1Api);
    this.cfg = cfg;
  }

  public getCfg(): Auth0Config {
    return this.cfg;
  }

  private async loadSecrets(): Promise<Auth0SecretMap> {
    const client = new ManagementClient({
      domain: this.cfg.auth0Domain,
      clientId: this.cfg.auth0MgtClientId,
      clientSecret: this.cfg.auth0MgtClientSecret,
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
        this.cfg.fixedTokenCacheName,
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
          name: this.cfg.fixedTokenCacheName,
        },
        data,
      });
    } catch (_) {
      try {
        console.log('Deleting existing secret');
        await this.k8sApi.deleteNamespacedSecret(this.cfg.fixedTokenCacheName, 'default');

        console.log('Creating new secret');
        await this.k8sApi.createNamespacedSecret('default', {
          apiVersion: 'v1',
          kind: 'Secret',
          metadata: {
            name: this.cfg.fixedTokenCacheName,
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

  public async getClientAccessToken(
    clientId: string,
    clientSecret: string,
    audience?: string
  ): Promise<string> {
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

    const aud = audience || 'https://canton.network.global';

    pulumi.log.info(
      'Querying access token for Auth0 client: ' + clientId + ' with audience ' + aud
    );
    const auth0 = new AuthenticationClient({
      domain: this.cfg.auth0Domain,
      clientId: clientId,
      clientSecret: clientSecret,
    });

    const tokenResponse = await auth0.clientCredentialsGrant({
      audience: aud,
    });

    const { expires_in } = tokenResponse;

    if (expires_in < REQUIRED_TOKEN_LIFETIME) {
      /* If you see this error, you either need to decrease the required token
       * lifetime or extend the length of the tokens issued by Auth0
       * (configured in the configuration of the ledger-api API in auth0).
       */
      console.error(
        `Auth0 access token issued with expiry (${expires_in}) too short to meet REQUIRED_TOKEN_LIFETIME (${REQUIRED_TOKEN_LIFETIME})`
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

export function requireAuth0ClientId(clientIdMap: ClientIdMap, clientName: string): string {
  const appClientId = clientIdMap[clientName];

  if (!appClientId) {
    throw new Error(`Unknown Auth0 client ID for client: ${clientName}`);
  }

  return appClientId;
}

function lookupClientSecrets(
  allSecrets: Auth0SecretMap,
  clientIdMap: ClientIdMap,
  clientName: string
): Auth0ClientSecret {
  const appClientId = requireAuth0ClientId(clientIdMap, clientName);

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
  const cfg = auth0Client.getCfg();
  const clientSecrets = lookupClientSecrets(allSecrets, cfg.appToClientId, clientName);
  const audience = cfg.appToClientAudience[clientName];

  const clientId = clientSecrets.client_id;
  const clientSecret = clientSecrets.client_secret;

  if (fixedTokens()) {
    const accessToken = await auth0Client.getClientAccessToken(clientId, clientSecret, audience);
    return {
      token: accessToken,
      'ledger-api-user': clientId + '@clients',
    };
  } else {
    return {
      url: `https://${cfg.auth0Domain}/.well-known/openid-configuration`,
      'client-id': clientId,
      'client-secret': clientSecret,
      'ledger-api-user': clientId + '@clients',
      ...(audience && { audience: audience }),
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

export async function installAuth0UISecret(
  auth0Client: Auth0Client,
  xns: ExactNamespace,
  secretNameApp: string,
  clientName: string
): Promise<k8s.core.v1.Secret> {
  const clientSecrets = lookupClientSecrets(
    await auth0Client.getSecrets(),
    auth0Client.getCfg().namespaceToUiClientId,
    xns.logicalName
  );
  const clientId = clientSecrets.client_id;

  return installAuth0UiSecretWithClientId(auth0Client, xns, secretNameApp, clientName, clientId);
}

export function installAuth0UiSecretWithClientId(
  auth0Client: Auth0Client,
  xns: ExactNamespace,
  secretNameApp: string,
  clientName: string,
  clientId: string
): k8s.core.v1.Secret {
  return new k8s.core.v1.Secret(
    'auth0-ui-secret-' + xns.logicalName + '-' + clientName,
    {
      metadata: {
        name: 'cn-app-' + secretNameApp + '-ui-auth',
        namespace: xns.ns.metadata.name,
      },
      stringData: {
        url: `https://${auth0Client.getCfg().auth0Domain}`,
        'client-id': clientId,
      },
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
    valueFrom: auth0UserNameEnvVarSource(secretName),
  };
}

export function auth0UserNameEnvVarSource(
  secretName: string
): k8s.types.input.core.v1.EnvVarSource {
  return {
    secretKeyRef: {
      key: 'ledger-api-user',
      name: `cn-app-${secretName.toLowerCase().replaceAll('_', '-')}-ledger-api-auth`,
      optional: false,
    },
  };
}
