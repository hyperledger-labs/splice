// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';

import { ExactNamespace, fixedTokens } from '../utils';
import { DEFAULT_AUDIENCE } from './audiences';
import { requireAuth0ClientId } from './auth0';
import { Auth0Client, Auth0ClientSecret, Auth0SecretMap, ClientIdMap } from './auth0types';

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

// TODO(#2873): for now we still export this for splitwell, reconsider
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

// TODO(#2873): for now we still export this for splitwell, reconsider
export async function installAuth0UISecret(
  auth0Client: Auth0Client,
  xns: ExactNamespace,
  secretNameApp: string,
  clientName?: string // TODO(#2873): remove this after applying once
): Promise<k8s.core.v1.Secret> {
  const secrets = await auth0Client.getSecrets();
  const namespaceClientIds = auth0Client.getCfg().namespaceToUiToClientId[xns.logicalName];
  if (!namespaceClientIds) {
    throw new Error(`No Auth0 client IDs configured for namespace: ${xns.logicalName}`);
  }
  const id = lookupClientSecrets(secrets, namespaceClientIds, secretNameApp).client_id;

  return installAuth0UiSecretWithClientId(auth0Client, xns, secretNameApp, id, clientName);
}

export function installAuth0UiSecretWithClientId(
  auth0Client: Auth0Client,
  xns: ExactNamespace,
  secretNameApp: string,
  clientId: string | Promise<string>,
  clientName?: string // TODO(#2873): remove this, and the alias, after applying once
): k8s.core.v1.Secret {
  return new k8s.core.v1.Secret(
    `splice-auth0-ui-secret-${xns.logicalName}-${secretNameApp}`,
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
      aliases: clientName ? [`splice-auth0-ui-secret-${xns.logicalName}-${clientName}`] : [],
    }
  );
}

function getNameSpaceAuth0Clients(auth0Client: Auth0Client, ns: ExactNamespace): ClientIdMap {
  const auth0Config = auth0Client.getCfg();
  const svNameSpaceAuth0Clients = auth0Config.namespaceToUiToClientId[ns.logicalName];
  if (!svNameSpaceAuth0Clients) {
    throw new Error(`No ${ns.logicalName} namespace in auth0 config`);
  }
  return svNameSpaceAuth0Clients;
}

function getUiClientId(auth0Client: Auth0Client, ns: ExactNamespace, appName: string): string {
  const clientId = getNameSpaceAuth0Clients(auth0Client, ns)[appName];
  if (!clientId) {
    throw new Error(`No ${appName} ui client id in auth0 config`);
  }
  return clientId;
}

export async function installValidatorSecrets(
  ns: ExactNamespace,
  auth0Client: Auth0Client,
  auth0ValidatorAppName?: string
): Promise<k8s.core.v1.Secret[]> {
  const walletUiClientId = getUiClientId(auth0Client, ns, 'wallet');
  const cnsUiClientId = getUiClientId(auth0Client, ns, 'cns');

  return [
    await installAuth0Secret(auth0Client, ns, 'validator', auth0ValidatorAppName || 'validator'),
    installAuth0UiSecretWithClientId(auth0Client, ns, 'wallet', walletUiClientId),
    installAuth0UiSecretWithClientId(auth0Client, ns, 'cns', cnsUiClientId),
  ];
}

export async function installSvAppSecrets(
  ns: ExactNamespace,
  auth0Client: Auth0Client,
  auth0SvAppName: string, // TODO(#2873): try to get rid of this
  uiSecretClientName?: string // TODO(#2873): remove this after applying once
): Promise<k8s.core.v1.Secret[]> {
  const clientId = getUiClientId(auth0Client, ns, 'sv');

  return [
    await installAuth0Secret(auth0Client, ns, 'sv', auth0SvAppName),
    installAuth0UiSecretWithClientId(auth0Client, ns, 'sv', clientId, uiSecretClientName),
  ];
}
