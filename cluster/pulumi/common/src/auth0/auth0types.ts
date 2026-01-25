// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
export interface Auth0ClientSecret {
  client_id: string;
  client_secret: string;
}

export type Auth0SecretMap = Map<string, Auth0ClientSecret>;

export type Auth0NamespaceAudiences = {
  ledgerApi: string;
  validatorApi: string;
  svAppApi?: string; // Empty for validator-only namespaces
};

export type Auth0NamespaceBackendClientIds = {
  validator: string;
  svApp?: string; // Empty for validator-only namespaces
  splitwell?: string;
};

export type Auth0NamespaceUiClientIds = {
  wallet: string;
  cns: string;
  sv?: string; // Empty for validator-only namespaces
  splitwell?: string;
};

export type Auth0NamespaceConfig = {
  audiences: Auth0NamespaceAudiences;
  backendClientIds: Auth0NamespaceBackendClientIds;
  uiClientIds: Auth0NamespaceUiClientIds;
};

// Map<> doesn't work as Pulumi outputs, so we use Record<> here. See https://github.com/pulumi/pulumi/issues/9787
export type NamespacedAuth0Configs = Record<string, Auth0NamespaceConfig>;

export type Auth0Config = {
  namespacedConfigs: NamespacedAuth0Configs;
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
    audience: string
  ) => Promise<string>;
  getCfg: () => Auth0Config;
  reuseNamespaceConfig(fromNamespace: string, toNamespace: string): void;
}

export type Auth0ClusterConfig = {
  cantonNetwork?: Auth0Config;
  svRunbook?: Auth0Config;
  validatorRunbook?: Auth0Config;
  mainnet?: Auth0Config;
};
