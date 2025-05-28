// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
export interface Auth0ClientSecret {
  client_id: string;
  client_secret: string;
}

export type Auth0SecretMap = Map<string, Auth0ClientSecret>;

export type ClientIdMap = Partial<Record<string, string>>;

export type NamespaceToClientIdMapMap = Partial<Record<string, ClientIdMap>>;

export type AudienceMap = Partial<Record<string, string>>;

export type Auth0Config = {
  appToClientId: ClientIdMap;
  namespaceToUiToClientId: NamespaceToClientIdMapMap;
  appToApiAudience: AudienceMap;
  appToClientAudience: AudienceMap;
  auth0Domain: string;
  auth0MgtClientId: string;
  auth0MgtClientSecret: string;
  fixedTokenCacheName: string;
};

export interface Auth0ClientAccessToken {
  accessToken: string;
  expiry: string;
}

export interface Auth0Client {
  getSecrets: () => Promise<Auth0SecretMap>;
  getClientAccessToken: (
    clientId: string,
    clientSecret: string,
    audience?: string
  ) => Promise<string>;
  getCfg: () => Auth0Config;
}

export type Auth0ClusterConfig = {
  cantonNetwork?: Auth0Config;
  svRunbook?: Auth0Config;
  validatorRunbook?: Auth0Config;
  mainnet?: Auth0Config;
};
