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

function getNameSpaceAuth0Clients(auth0Client: Auth0Client, ns: ExactNamespace): ClientIdMap {
  const auth0Config = auth0Client.getCfg();
  const svNameSpaceAuth0Clients = auth0Config.namespaceToUiToClientId[ns.logicalName];
  if (!svNameSpaceAuth0Clients) {
    throw new Error(`No ${ns.logicalName} namespace in auth0 config`);
  }
  return svNameSpaceAuth0Clients;
}

function getUiClientId(
  auth0Client: Auth0Client,
  ns: ExactNamespace,
  appName: string
): string {
  const clientId = getNameSpaceAuth0Clients(auth0Client, ns)[appName];
  if (!clientId) {
    throw new Error(`No ${appName} ui client id in auth0 config`);
  }
  return clientId;
}

export async function installValidatorSecrets(
  ns: ExactNamespace,
  auth0Client: Auth0Client
): Promise<k8s.core.v1.Secret[]> {
  const clientId = getUiClientId(auth0Client, ns, 'wallet');

  return [
    await installAuth0Secret(auth0Client, ns, 'validator', 'validator'),
    uiSecret(auth0Client, ns, 'wallet', clientId),
  ];
}

export function cnsUiSecret(ns: ExactNamespace, auth0Client: Auth0Client): k8s.core.v1.Secret {
  const clientId = getUiClientId(auth0Client, ns, 'cns');

  return uiSecret(auth0Client, ns, 'cns', clientId);
}

export async function installSvAppSecrets(
  ns: ExactNamespace,
  auth0Client: Auth0Client,
  auth0SvAppName: string // FIXME: try to get rid of this
): Promise<k8s.core.v1.Secret[]> {
  const clientId = getUiClientId(auth0Client, ns, 'sv');

  return [
    await installAuth0Secret(auth0Client, ns, 'sv', auth0SvAppName),
    uiSecret(auth0Client, ns, 'sv', clientId),
  ];
}
