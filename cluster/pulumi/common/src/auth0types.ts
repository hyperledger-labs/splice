export interface Auth0ClientSecret {
  client_id: string;
  client_secret: string;
}

export type Auth0SecretMap = Map<string, Auth0ClientSecret>;

export type ClientIdMap = Partial<Record<string, string>>;

export type Auth0Config = {
  appToClientId: ClientIdMap;
  namespaceToUiClientId: ClientIdMap;
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
  getClientAccessToken: (clientId: string, clientSecret: string) => Promise<string>;
  getCfg: () => Auth0Config;
}
