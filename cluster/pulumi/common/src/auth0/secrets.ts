// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';

import { isMainNet } from '../config';
import { ExactNamespace, fixedTokens } from '../utils';
import { getNamespaceConfig } from './auth0';
import { Auth0Client, Auth0ClientSecret, Auth0SecretMap } from './auth0types';

function lookupClientSecrets(
  allSecrets: Auth0SecretMap,
  auth0Client: Auth0Client,
  namespace: string,
  app: 'sv' | 'validator' | 'splitwell'
): Auth0ClientSecret {
  const appClientId = getClientId(auth0Client, app, namespace);

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

function getClientId(
  auth0Client: Auth0Client,
  clientName: 'sv' | 'validator' | 'splitwell',
  namespace: string
): string {
  const cfg = auth0Client.getCfg();
  switch (clientName) {
    case 'sv':
      return getNamespaceConfig(cfg, namespace).backendClientIds.svApp!;
    case 'validator':
      return getNamespaceConfig(cfg, namespace).backendClientIds.validator;
    case 'splitwell':
      return getNamespaceConfig(cfg, namespace).backendClientIds.splitwell!;
  }
}

function getUiClientId(
  auth0Client: Auth0Client,
  uiName: 'wallet' | 'cns' | 'sv' | 'splitwell',
  namespace: string
): string {
  const cfg = auth0Client.getCfg();
  if (!cfg.namespacedConfigs[namespace]) {
    throw new Error(`No Auth0 configuration for namespace ${namespace}`);
  }
  switch (uiName) {
    case 'wallet':
      return getNamespaceConfig(cfg, namespace).uiClientIds.wallet;
    case 'cns':
      return getNamespaceConfig(cfg, namespace).uiClientIds.cns;
    case 'sv':
      return getNamespaceConfig(cfg, namespace).uiClientIds.sv!;
    case 'splitwell':
      return getNamespaceConfig(cfg, namespace).uiClientIds.splitwell!;
  }
}

async function ledgerApiSecretContent(
  auth0Client: Auth0Client,
  allSecrets: Auth0SecretMap,
  clientName: 'sv' | 'validator' | 'splitwell',
  namespace: string
): Promise<{ [key: string]: string }> {
  const cfg = auth0Client.getCfg();
  const clientId = getClientId(auth0Client, clientName, namespace);

  const clientSecrets = lookupClientSecrets(allSecrets, auth0Client, namespace, clientName);
  const audience = cfg.namespacedConfigs[namespace]!.audiences.ledgerApi;

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
  clientName: 'sv' | 'validator' | 'splitwell'
): Promise<k8s.core.v1.Secret> {
  const secrets = await auth0Client.getSecrets();
  const secret = await ledgerApiSecretContent(auth0Client, secrets, clientName, xns.logicalName);
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

// TODO(#2873): remove this after applying once
function legacyResourceName(
  xns: ExactNamespace,
  clientName: 'sv' | 'validator' | 'splitwell'
): string {
  // Currently, for no good reason, we have sv-1 vs sv1_validator naming inconsistency.
  // To make things worse, the sv-da-1 namespace is even more special and has sv-da-1 vs sv-da-1_validator.
  // Then mainnet DA-2 is even worse, as we use "sv" and "validator"

  if (xns.logicalName == 'sv-da-1') {
    if (clientName == 'sv') {
      return `splice-auth0-secret-${xns.logicalName}-sv-da-1`;
    }
    if (clientName == 'validator') {
      return `splice-auth0-secret-${xns.logicalName}-sv-da-1_validator`;
    }
  }
  if (isMainNet && xns.logicalName == 'da-2') {
    // These are actually identical to the "new" names, but returning them here nevertheless for completeness
    if (clientName == 'sv') {
      return `splice-auth0-secret-${xns.logicalName}-sv`;
    }
    if (clientName == 'validator') {
      return `splice-auth0-secret-${xns.logicalName}-validator`;
    }
  }
  if (xns.logicalName.startsWith('sv-')) {
    if (clientName == 'sv') {
      return `splice-auth0-secret-${xns.logicalName}-${xns.logicalName}`;
    }
    if (clientName == 'validator') {
      return `splice-auth0-secret-${xns.logicalName}-${xns.logicalName.replace('-', '')}_validator`;
    }
  }
  if (xns.logicalName == 'splitwell') {
    if (clientName == 'validator') {
      return `splice-auth0-secret-${xns.logicalName}-splitwell_validator`;
    }
    return '';
  }
  if (xns.logicalName == 'validator1') {
    if (clientName == 'validator') {
      return `splice-auth0-secret-validator1-validator1`;
    }
  }

  return '';
}

// TODO(#2873): for now we still export this for splitwell, reconsider
export async function installLedgerApiSecret(
  auth0Client: Auth0Client,
  xns: ExactNamespace,
  clientName: 'sv' | 'validator' | 'splitwell'
): Promise<k8s.core.v1.Secret> {
  const secrets = await auth0Client.getSecrets();
  const secret = await ledgerApiSecretContent(auth0Client, secrets, clientName, xns.logicalName);

  return new k8s.core.v1.Secret(
    `splice-auth0-secret-${xns.logicalName}-${clientName}`,
    {
      metadata: {
        name: `splice-app-${clientName}-ledger-api-auth`,
        namespace: xns.ns.metadata.name,
      },
      stringData: secret,
    },
    {
      dependsOn: xns.ns,
      // TODO(#2873): remove the alias after applying once
      aliases: [{ name: legacyResourceName(xns, clientName) }],
    }
  );
}

// TODO(#2873): for now we still export this for splitwell, reconsider
export async function installAuth0UISecret(
  auth0Client: Auth0Client,
  xns: ExactNamespace,
  secretNameApp: 'wallet' | 'cns' | 'sv' | 'splitwell',
  clientName?: string // TODO(#2873): remove this after applying once
): Promise<k8s.core.v1.Secret> {
  const clientId = getUiClientId(auth0Client, secretNameApp, xns.logicalName);

  return installAuth0UiSecretWithClientId(auth0Client, xns, secretNameApp, clientId, clientName);
}

function installAuth0UiSecretWithClientId(
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
      aliases: clientName
        ? [{ name: `splice-auth0-ui-secret-${xns.logicalName}-${clientName}` }]
        : [],
    }
  );
}

export async function installValidatorSecrets(
  ns: ExactNamespace,
  auth0Client: Auth0Client
): Promise<k8s.core.v1.Secret[]> {
  const walletUiClientId = getUiClientId(auth0Client, 'wallet', ns.logicalName);
  const cnsUiClientId = getUiClientId(auth0Client, 'cns', ns.logicalName);

  return [
    await installLedgerApiSecret(auth0Client, ns, 'validator'),
    installAuth0UiSecretWithClientId(auth0Client, ns, 'wallet', walletUiClientId),
    installAuth0UiSecretWithClientId(auth0Client, ns, 'cns', cnsUiClientId),
  ];
}

export async function installSvAppSecrets(
  ns: ExactNamespace,
  auth0Client: Auth0Client
): Promise<k8s.core.v1.Secret[]> {
  const clientId = getUiClientId(auth0Client, 'sv', ns.logicalName);

  return [
    await installLedgerApiSecret(auth0Client, ns, 'sv'),
    installAuth0UiSecretWithClientId(auth0Client, ns, 'sv', clientId),
  ];
}
