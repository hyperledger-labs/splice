// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';

import { ExactNamespace } from '../utils';
import { installAuth0Secret, installAuth0UiSecretWithClientId } from './auth0';
import { Auth0Client, ClientIdMap } from './auth0types';

export function uiSecret(
  auth0Client: Auth0Client,
  ns: ExactNamespace,
  appName: string,
  clientId: string
): k8s.core.v1.Secret {
  return installAuth0UiSecretWithClientId(auth0Client, ns, appName, appName, clientId);
}

export type AppAndUiSecrets = {
  appSecret: k8s.core.v1.Secret;
  uiSecret: k8s.core.v1.Secret;
};

function getSvNameSpaceAuth0Clients(
  auth0Client: Auth0Client,
  ns: ExactNamespace
): ClientIdMap {
  const auth0Config = auth0Client.getCfg();
  const svNameSpaceAuth0Clients = auth0Config.namespaceToUiToClientId[ns.logicalName];
  if (!svNameSpaceAuth0Clients) {
    throw new Error(`No ${ns.logicalName} namespace in auth0 config`);
  }
  return svNameSpaceAuth0Clients;
}

export async function validatorSecrets(
  ns: ExactNamespace,
  auth0Client: Auth0Client,
): Promise<AppAndUiSecrets> {

  const clientId = getSvNameSpaceAuth0Clients(auth0Client, ns)['wallet'];
  if (!clientId) {
    throw new Error('No Wallet ui client id in auth0 config');
  }

  return {
    appSecret: await installAuth0Secret(auth0Client, ns, 'validator', 'validator'),
    uiSecret: uiSecret(auth0Client, ns, 'wallet', clientId),
  };
}

export function cnsUiSecret(
  ns: ExactNamespace,
  auth0Client: Auth0Client,
): k8s.core.v1.Secret {

  const clientId = getSvNameSpaceAuth0Clients(auth0Client, ns)['cns'];
  if (!clientId) {
    throw new Error('No CNS ui client id in auth0 config');
  }

  return uiSecret(auth0Client, ns, 'cns', clientId);
}
