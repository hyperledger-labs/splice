// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import { KubeConfig, CoreV1Api } from '@kubernetes/client-node';
import { getSecretVersionOutput } from '@pulumi/gcp/secretmanager';
import { Output } from '@pulumi/pulumi';
import { AuthenticationClient, ManagementClient, TokenSet } from 'auth0';

import { config, isMainNet } from '../config';
import { CLUSTER_BASENAME, ExactNamespace, fixedTokens } from '../utils';
import type {
  Auth0Client,
  Auth0SecretMap,
  Auth0ClientAccessToken,
  Auth0ClientSecret,
  ClientIdMap,
  Auth0Config,
  Auth0ClusterConfig,
} from './auth0types';

type Auth0CacheMap = Record<string, Auth0ClientAccessToken>;

export const DEFAULT_AUDIENCE = 'https://canton.network.global';

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
  private hasDiffsToSave = false;

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
      // scope: 'read:clients read:client_keys',
      retry: {
        enabled: true,
        maxRetries: 10,
      },
    });

    const secrets = new Map() as Auth0SecretMap;
    let page = 0;
    /* eslint-disable no-constant-condition */
    while (true) {
      const clients = await client.clients.getAll({
        per_page: 50, // Even though 50 is the default, if it's not given explicitly, the page argument is ignored
        page: page++,
      });

      if (clients.data.length === 0) {
        return secrets;
      }

      for (const client of clients.data) {
        if (client.client_id && client.client_secret) {
          secrets.set(client.client_id, client as Auth0ClientSecret);
        }
      }
    }
  }

  public async loadAuth0Cache(): Promise<void> {
    await pulumi.log.debug('Loading Auth0 Cache');
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

      this.auth0Cache = cacheMap;
      await pulumi.log.debug('Auth0 cache loaded...');
    } catch (e) {
      this.auth0Cache = undefined;
      await pulumi.log.debug('No Auth0 cache secret found.');
    }
  }

  public async saveAuth0Cache(): Promise<void> {
    const data = {} as Record<string, string>;
    await pulumi.log.debug('Saving Auth0 cache');

    if (!this.auth0Cache) {
      await pulumi.log.debug('No auth0 cache loaded in Auth0Fetch');
      return;
    }

    if (!this.hasDiffsToSave) {
      await pulumi.log.debug('No auth0 cache diffs to save');
      return;
    }

    for (const clientId in this.auth0Cache) {
      const cachedToken = this.auth0Cache[clientId];

      data[clientId] = Buffer.from(JSON.stringify(cachedToken)).toString('base64');
    }

    try {
      await pulumi.log.info('Attempting to create secret');
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
        await pulumi.log.info('Deleting existing secret');
        await this.k8sApi.deleteNamespacedSecret(this.cfg.fixedTokenCacheName, 'default');

        await pulumi.log.info('Creating new secret');
        await this.k8sApi.createNamespacedSecret('default', {
          apiVersion: 'v1',
          kind: 'Secret',
          metadata: {
            name: this.cfg.fixedTokenCacheName,
          },
          data,
        });
      } catch (e) {
        await pulumi.log.error(`Auth0 cache update failed: ${JSON.stringify(e)}`);
        process.exit(1);
      }
    }

    await pulumi.log.debug('Auth0 cache saved');
  }

  public async getSecrets(): Promise<Auth0SecretMap> {
    if (this.secrets === undefined) {
      await pulumi.log.debug('Calling Auth0 API for getSecrets()');
      this.secrets = await this.loadSecrets();
    }
    return this.secrets;
  }

  public async getClientAccessToken(
    clientId: string,
    clientSecret: string,
    audience?: string
  ): Promise<string> {
    await pulumi.log.debug('Getting access token for Auth0 client: ' + clientId);

    const now = new Date();

    if (this.auth0Cache) {
      const cachedSecret = this.auth0Cache[clientId];
      if (cachedSecret) {
        const cachedSecretExpiry = new Date(cachedSecret.expiry);
        if (addTimeSeconds(now, REQUIRED_TOKEN_LIFETIME) > cachedSecretExpiry) {
          await pulumi.log.info('Ignoring expired cached Auth0 token for client: ' + clientId);
        } else {
          await pulumi.log.debug('Using cached Auth0 token for client: ' + clientId);
          return cachedSecret.accessToken;
        }
      }
    }

    const aud = audience || DEFAULT_AUDIENCE;

    await pulumi.log.debug(
      'Querying access token for Auth0 client: ' + clientId + ' with audience ' + aud
    );
    const auth0 = new AuthenticationClient({
      domain: this.cfg.auth0Domain,
      clientId: clientId,
      clientSecret: clientSecret,
    });

    const tokenResponse = await auth0.oauth.clientCredentialsGrant({
      audience: aud,
    });

    const { expires_in } = tokenResponse.data;

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

    await this.cacheNewToken(clientId, expiry, tokenResponse.data);

    return tokenResponse.data.access_token;
  }

  private async cacheNewToken(clientId: string, expiry: Date, tokenResponse: TokenSet) {
    await pulumi.log.debug(
      'Caching access token for Auth0 client: ' + clientId + ' expiry: ' + expiry.toJSON()
    );

    if (this.auth0Cache && tokenResponse.access_token) {
      this.hasDiffsToSave = true;
      this.auth0Cache[clientId] = {
        accessToken: tokenResponse.access_token,
        expiry: expiry.toJSON(),
      };
    }
  }
}

export function requireAuth0ClientId(clientIdMap: ClientIdMap, app: string): string {
  const appClientId = clientIdMap[app];

  if (!appClientId) {
    throw new Error(`Unknown Auth0 client ID for app: ${app}, ${JSON.stringify(clientIdMap)}`);
  }

  return appClientId;
}

function lookupClientSecrets(
  allSecrets: Auth0SecretMap,
  clientIdMap: ClientIdMap,
  app: string
): Auth0ClientSecret {
  const appClientId = requireAuth0ClientId(clientIdMap, app);

  const clientSecret = allSecrets.get(appClientId);

  if (!clientSecret) {
    throw new Error(`Client unknown to Auth0: ${app} (Client ID: ${appClientId})`);
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
  const audience: string = cfg.appToClientAudience[clientName] || DEFAULT_AUDIENCE;

  const clientId = clientSecrets.client_id;
  const clientSecret = clientSecrets.client_secret;

  if (fixedTokens()) {
    const accessToken = await auth0Client.getClientAccessToken(clientId, clientSecret, audience);
    return {
      audience,
      token: accessToken,
      'ledger-api-user': clientId + '@clients',
    };
  } else {
    return {
      audience,
      url: `https://${cfg.auth0Domain}/.well-known/openid-configuration`,
      'client-id': clientId,
      'client-secret': clientSecret,
      'ledger-api-user': clientId + '@clients',
    };
  }
}

export async function installLedgerApiUserSecret(
  auth0Client: Auth0Client,
  xns: ExactNamespace,
  secretNameApp: string,
  clientName: string
): Promise<k8s.core.v1.Secret> {
  const secrets = await auth0Client.getSecrets();
  const secret = await auth0Secret(auth0Client, secrets, clientName);
  const ledgerApiUserOnly = {
    'ledger-api-user': secret['ledger-api-user'],
  };

  return new k8s.core.v1.Secret(
    `splice-auth0-user-${xns.logicalName}-${secretNameApp}-${clientName}`,
    {
      metadata: {
        name: `splice-app-${secretNameApp}-ledger-api-user`,
        namespace: xns.ns.metadata.name,
      },
      stringData: ledgerApiUserOnly,
    },
    {
      dependsOn: xns.ns,
    }
  );
}

export async function installAuth0Secret(
  auth0Client: Auth0Client,
  xns: ExactNamespace,
  secretNameApp: string,
  clientName: string
): Promise<k8s.core.v1.Secret> {
  const secrets = await auth0Client.getSecrets();
  const secret = await auth0Secret(auth0Client, secrets, clientName);

  return new k8s.core.v1.Secret(
    `splice-auth0-secret-${xns.logicalName}-${clientName}`,
    {
      metadata: {
        name: `splice-app-${secretNameApp}-ledger-api-auth`,
        namespace: xns.ns.metadata.name,
      },
      stringData: secret,
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
  const secrets = await auth0Client.getSecrets();
  const namespaceClientIds = auth0Client.getCfg().namespaceToUiToClientId[xns.logicalName];
  if (!namespaceClientIds) {
    throw new Error(`No Auth0 client IDs configured for namespace: ${xns.logicalName}`);
  }
  const id = lookupClientSecrets(secrets, namespaceClientIds, secretNameApp).client_id;

  return installAuth0UiSecretWithClientId(auth0Client, xns, secretNameApp, clientName, id);
}

export function installAuth0UiSecretWithClientId(
  auth0Client: Auth0Client,
  xns: ExactNamespace,
  secretNameApp: string,
  clientName: string,
  clientId: string | Promise<string>
): k8s.core.v1.Secret {
  return new k8s.core.v1.Secret(
    `splice-auth0-ui-secret-${xns.logicalName}-${clientName}`,
    {
      metadata: {
        name: `splice-app-${secretNameApp}-ui-auth`,
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
    name: `SPLICE_APP_${name.toUpperCase()}_LEDGER_API_AUTH_USER_NAME`,
    valueFrom: auth0UserNameEnvVarSource(secretName),
  };
}

export function auth0UserNameEnvVarSource(
  secretName: string,
  userOnlySecret: boolean = false
): k8s.types.input.core.v1.EnvVarSource {
  return {
    secretKeyRef: {
      key: 'ledger-api-user',
      name: `splice-app-${secretName.toLowerCase().replaceAll('_', '-')}-ledger-api-${userOnlySecret ? 'user' : 'auth'}`,
      optional: false,
    },
  };
}

export enum Auth0ClientType {
  RUNBOOK,
  MAINSTACK,
}

export function getAuth0Config(clientType: Auth0ClientType): Output<Auth0Fetch> {
  const infraStack = new pulumi.StackReference(`organization/infra/infra.${CLUSTER_BASENAME}`);
  const auth0ClusterCfg = infraStack.requireOutput('auth0') as pulumi.Output<Auth0ClusterConfig>;
  switch (clientType) {
    case Auth0ClientType.RUNBOOK:
      if (!auth0ClusterCfg.svRunbook) {
        throw new Error('missing sv runbook auth0 output');
      }
      return auth0ClusterCfg.svRunbook.apply(cfg => {
        if (!cfg) {
          throw new Error('missing sv runbook auth0 output');
        }
        cfg.auth0MgtClientSecret = config.requireEnv('AUTH0_SV_MANAGEMENT_API_CLIENT_SECRET');
        return new Auth0Fetch(cfg);
      });
    case Auth0ClientType.MAINSTACK:
      if (isMainNet) {
        if (!auth0ClusterCfg.mainnet) {
          throw new Error('missing mainNet auth0 output');
        }
        return auth0ClusterCfg.mainnet.apply(cfg => {
          if (!cfg) {
            throw new Error('missing mainNet auth0 output');
          }
          cfg.auth0MgtClientSecret = config.requireEnv('AUTH0_MAIN_MANAGEMENT_API_CLIENT_SECRET');
          return new Auth0Fetch(cfg);
        });
      } else {
        if (!auth0ClusterCfg.cantonNetwork) {
          throw new Error('missing cantonNetwork auth0 output');
        }
        return auth0ClusterCfg.cantonNetwork.apply(cfg => {
          if (!cfg) {
            throw new Error('missing cantonNetwork auth0 output');
          }
          cfg.auth0MgtClientSecret = config.requireEnv('AUTH0_CN_MANAGEMENT_API_CLIENT_SECRET');
          return new Auth0Fetch(cfg);
        });
      }
  }
}

export const svUserIds = (auth0Cfg: Auth0Config): Output<string[]> => {
  const temp = getSecretVersionOutput({
    secret: `pulumi-user-configs-${auth0Cfg.auth0Domain.replace('.us.auth0.com', '')}`,
  });
  return temp.apply(config => {
    const secretData = config.secretData;
    const json = JSON.parse(secretData);
    const ret: string[] = [];
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    json.forEach((user: any) => {
      ret.push(user.user_id);
    });
    return ret;
  });
};

export const ansDomainPrefix = 'cns';
