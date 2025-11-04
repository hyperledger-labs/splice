// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import { KubeConfig, CoreV1Api } from '@kubernetes/client-node';
import { getSecretVersionOutput } from '@pulumi/gcp/secretmanager';
import { Output } from '@pulumi/pulumi';
import { AuthenticationClient, ManagementClient, TokenSet } from 'auth0';

import { config, isMainNet } from '../config';
import { infraStack } from '../stackReferences';
import { fixedTokens } from '../utils';
import { DEFAULT_AUDIENCE } from './audiences';
import type {
  Auth0Client,
  Auth0SecretMap,
  Auth0ClientAccessToken,
  Auth0ClientSecret,
  Auth0Config,
  Auth0ClusterConfig,
  Auth0NamespaceConfig,
  NamespacedAuth0Configs,
} from './auth0types';

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

  // Any namespace that we deploy using validator-runbook stack currently reuses the
  // Auth0 artifacts from the 'validator' namespace, so we just copy the config over
  // so that any lookup by namespace will just work.
  public reuseNamespaceConfig(fromNamespace: string, toNamespace: string): void {
    if (fromNamespace === toNamespace) {
      // Nothing to do
      return;
    }
    if (!this.cfg.namespacedConfigs[fromNamespace]) {
      throw new Error(`No Auth0 configuration for namespace ${fromNamespace}`);
    }
    this.cfg.namespacedConfigs[toNamespace] = this.cfg.namespacedConfigs[fromNamespace];
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
    if (!fixedTokens()) {
      await pulumi.log.debug('Fixed tokens not enabled, skipping Auth0 cache load');
      this.auth0Cache = undefined;
      return;
    }

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

      await pulumi.log.debug('Auth0 cache loaded...');
    } catch (e) {
      await pulumi.log.warn('No Auth0 cache secret found');
    }

    this.auth0Cache = cacheMap;
  }

  public async saveAuth0Cache(): Promise<void> {
    const data = {} as Record<string, string>;
    await pulumi.log.debug('Saving Auth0 cache');

    if (!this.auth0Cache) {
      await pulumi.log.debug('No auth0 cache to save');
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
    audience: string
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

    await pulumi.log.debug(
      'Querying access token for Auth0 client: ' + clientId + ' with audience ' + audience
    );
    const auth0 = new AuthenticationClient({
      domain: this.cfg.auth0Domain,
      clientId: clientId,
      clientSecret: clientSecret,
    });

    const tokenResponse = await auth0.oauth.clientCredentialsGrant({
      audience,
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

export function getAuth0ClusterConfig(): Output<Auth0ClusterConfig> {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const infraOutput: pulumi.Output<any> = infraStack.requireOutput('auth0');
  return infraOutput.apply(output => {
    if (
      (output['cantonNetwork'] && output['cantonNetwork']['appToClientId'] === undefined) ||
      (output['mainNet'] && output['mainNet']['appToClientId'] === undefined)
    ) {
      // Infra is already on the new version, and its output is correctly typed
      return output as Auth0ClusterConfig;
    }

    // Infra is on the older version, and we need to massage it into the new shape
    const cnNamespaces: NamespacedAuth0Configs = {};
    let cn: Auth0Config | undefined = undefined;
    if (output['cantonNetwork']) {
      // TODO(#2873): remove this once infra on all clusters has been migrated
      for (let i = 1; i <= 16; i++) {
        if (output['cantonNetwork']['namespaceToUiToClientId'][`sv-${i}`]) {
          cnNamespaces[`sv-${i}`] = {
            audiences: {
              ledgerApi:
                output['cantonNetwork']['appToApiAudience']['participant'] || DEFAULT_AUDIENCE,
              validatorApi:
                output['cantonNetwork']['appToApiAudience']['validator'] || DEFAULT_AUDIENCE,
              svAppApi: output['cantonNetwork']['appToApiAudience']['sv'] || DEFAULT_AUDIENCE,
            },
            backendClientIds: {
              svApp: output['cantonNetwork']['appToClientId'][`sv-${i}`],
              validator: output['cantonNetwork']['appToClientId'][`sv${i}_validator`],
            },
            uiClientIds: {
              wallet: output['cantonNetwork']['namespaceToUiToClientId'][`sv-${i}`]['wallet'],
              cns: output['cantonNetwork']['namespaceToUiToClientId'][`sv-${i}`]['cns'],
              sv: output['cantonNetwork']['namespaceToUiToClientId'][`sv-${i}`]['sv'],
            },
          };
        }
      }

      if (output['cantonNetwork']['namespaceToUiToClientId']['sv-da-1']) {
        cnNamespaces['sv-da-1'] = {
          audiences: {
            ledgerApi:
              output['cantonNetwork']['appToApiAudience']['participant'] || DEFAULT_AUDIENCE,
            validatorApi:
              output['cantonNetwork']['appToApiAudience']['validator'] || DEFAULT_AUDIENCE,
            svAppApi: output['cantonNetwork']['appToApiAudience']['sv'] || DEFAULT_AUDIENCE,
          },
          backendClientIds: {
            svApp: output['cantonNetwork']['appToClientId']['sv-da-1']!,
            validator: output['cantonNetwork']['appToClientId']['sv-da-1_validator']!,
          },
          uiClientIds: {
            wallet: output['cantonNetwork']['namespaceToUiToClientId']['sv-da-1']['wallet']!,
            cns: output['cantonNetwork']['namespaceToUiToClientId']['sv-da-1']['cns']!,
            sv: output['cantonNetwork']['namespaceToUiToClientId']['sv-da-1']['sv']!,
          },
        };
      }

      if (output['cantonNetwork']['namespaceToUiToClientId']['splitwell']) {
        cnNamespaces['splitwell'] = {
          audiences: {
            ledgerApi:
              output['cantonNetwork']['appToApiAudience']['participant'] || DEFAULT_AUDIENCE,
            validatorApi:
              output['cantonNetwork']['appToApiAudience']['validator'] || DEFAULT_AUDIENCE,
            svAppApi: output['cantonNetwork']['appToApiAudience']['sv'] || DEFAULT_AUDIENCE,
          },
          backendClientIds: {
            validator: output['cantonNetwork']['appToClientId']['splitwell_validator']!,
            splitwell: output['cantonNetwork']['appToClientId']['splitwell']!,
          },
          uiClientIds: {
            splitwell:
              output['cantonNetwork']['namespaceToUiToClientId']['splitwell']['splitwell']!,
            wallet: output['cantonNetwork']['namespaceToUiToClientId']['splitwell']['wallet']!,
            cns: output['cantonNetwork']['namespaceToUiToClientId']['splitwell']['cns']!,
          },
        };
      }

      if (output['cantonNetwork']['namespaceToUiToClientId']['validator1']) {
        cnNamespaces['validator1'] = {
          audiences: {
            ledgerApi:
              output['cantonNetwork']['appToApiAudience']['participant'] || DEFAULT_AUDIENCE,
            validatorApi:
              output['cantonNetwork']['appToApiAudience']['validator'] || DEFAULT_AUDIENCE,
          },
          backendClientIds: {
            validator: output['cantonNetwork']['appToClientId']['validator1']!,
          },
          uiClientIds: {
            wallet: output['cantonNetwork']['namespaceToUiToClientId']['validator1']['wallet']!,
            cns: output['cantonNetwork']['namespaceToUiToClientId']['validator1']['cns']!,
            splitwell:
              output['cantonNetwork']['namespaceToUiToClientId']['validator1']['splitwell']!,
          },
        };
      }
      cn = {
        namespacedConfigs: cnNamespaces,
        auth0Domain: output['cantonNetwork']['auth0Domain']!,
        auth0MgtClientId: output['cantonNetwork']['auth0MgtClientId']!,
        auth0MgtClientSecret: output['cantonNetwork']['auth0MgtClientSecret']!,
        fixedTokenCacheName: output['cantonNetwork']['fixedTokenCacheName']!,
      };
    }

    const svRunbookNamespaces: NamespacedAuth0Configs = {};
    let svRunbook: Auth0Config | undefined = undefined;
    if (output['svRunbook']) {
      svRunbookNamespaces['sv'] = {
        audiences: {
          ledgerApi: output['svRunbook']['appToApiAudience']['participant'] || DEFAULT_AUDIENCE,
          validatorApi: output['svRunbook']['appToApiAudience']['validator'] || DEFAULT_AUDIENCE,
          svAppApi: output['svRunbook']['appToApiAudience']['sv'] || DEFAULT_AUDIENCE,
        },
        backendClientIds: {
          svApp: output['svRunbook']['appToClientId']['sv']!,
          validator: output['svRunbook']['appToClientId']['validator']!,
        },
        uiClientIds: {
          wallet: output['svRunbook']['namespaceToUiToClientId']['sv']['wallet']!,
          cns: output['svRunbook']['namespaceToUiToClientId']['sv']['cns']!,
          sv: output['svRunbook']['namespaceToUiToClientId']['sv']['sv']!,
        },
      };
      svRunbook = {
        namespacedConfigs: svRunbookNamespaces,
        auth0Domain: output['svRunbook']['auth0Domain']!,
        auth0MgtClientId: output['svRunbook']['auth0MgtClientId']!,
        auth0MgtClientSecret: output['svRunbook']['auth0MgtClientSecret']!,
        fixedTokenCacheName: output['svRunbook']['fixedTokenCacheName']!,
      };
    }

    let validatorRunbook: Auth0Config | undefined = undefined;
    if (output['validatorRunbook']) {
      const validatorRunbookNamespaces: NamespacedAuth0Configs = {};
      validatorRunbookNamespaces['validator'] = {
        audiences: {
          ledgerApi:
            output['validatorRunbook']['appToApiAudience']['participant'] || DEFAULT_AUDIENCE,
          validatorApi:
            output['validatorRunbook']['appToApiAudience']['validator'] || DEFAULT_AUDIENCE,
        },
        backendClientIds: {
          validator: output['validatorRunbook']['appToClientId']['validator']!,
        },
        uiClientIds: {
          wallet: output['validatorRunbook']['namespaceToUiToClientId']['validator']['wallet']!,
          cns: output['validatorRunbook']['namespaceToUiToClientId']['validator']['cns']!,
        },
      };
      validatorRunbook = {
        namespacedConfigs: validatorRunbookNamespaces,
        auth0Domain: output['validatorRunbook']['auth0Domain']!,
        auth0MgtClientId: output['validatorRunbook']['auth0MgtClientId']!,
        auth0MgtClientSecret: output['validatorRunbook']['auth0MgtClientSecret']!,
        fixedTokenCacheName: output['validatorRunbook']['fixedTokenCacheName']!,
      };
    }

    const mainNet: Auth0Config | undefined = undefined;
    if (output['mainNet']) {
      const mainNetNamespaces: NamespacedAuth0Configs = {};
      mainNetNamespaces['sv-1'] = {
        audiences: {
          ledgerApi: output['mainNet']['appToApiAudience']['participant'] || DEFAULT_AUDIENCE,
          validatorApi: output['mainNet']['appToApiAudience']['validator'] || DEFAULT_AUDIENCE,
          svAppApi: output['mainNet']['appToApiAudience']['sv'] || DEFAULT_AUDIENCE,
        },
        backendClientIds: {
          svApp: output['mainNet']['appToClientId']['sv']!,
          validator: output['mainNet']['appToClientId']['validator']!,
        },
        uiClientIds: {
          wallet: output['mainNet']['namespaceToUiToClientId']['sv-1']['wallet']!,
          cns: output['mainNet']['namespaceToUiToClientId']['sv-1']['cns']!,
          sv: output['mainNet']['namespaceToUiToClientId']['sv-1']['sv']!,
        },
      };
      mainNetNamespaces['sv-da-1'] = {
        audiences: {
          ledgerApi: output['mainNet']['appToApiAudience']['participant'] || DEFAULT_AUDIENCE,
          validatorApi: output['mainNet']['appToApiAudience']['validator'] || DEFAULT_AUDIENCE,
          svAppApi: output['mainNet']['appToApiAudience']['sv'] || DEFAULT_AUDIENCE,
        },
        backendClientIds: {
          svApp: output['mainNet']['appToClientId']['sv-da-1']!,
          validator: output['mainNet']['appToClientId']['sv-da-1_validator']!,
        },
        uiClientIds: {
          wallet: output['mainNet']['namespaceToUiToClientId']['sv-da-1']['wallet']!,
          cns: output['mainNet']['namespaceToUiToClientId']['sv-da-1']['cns']!,
          sv: output['mainNet']['namespaceToUiToClientId']['sv-da-1']['sv']!,
        },
      };
    }

    const newOutput: Auth0ClusterConfig = {
      cantonNetwork: cn,
      svRunbook: svRunbook,
      validatorRunbook: validatorRunbook,
      mainnet: mainNet,
    };
    return newOutput;
  });
}

export function getAuth0Config(clientType: Auth0ClientType): Output<Auth0Fetch> {
  const auth0ClusterCfg = getAuth0ClusterConfig();
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

export function getNamespaceConfig(
  auth0Config: Auth0Config,
  namespace: string
): Auth0NamespaceConfig {
  const nsConfig = auth0Config.namespacedConfigs[namespace];
  if (!nsConfig) {
    throw new Error(`No Auth0 configuration for namespace ${namespace}`);
  }
  return nsConfig;
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
