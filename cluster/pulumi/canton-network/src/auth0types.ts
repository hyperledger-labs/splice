import { TokenResponse } from 'auth0';

export interface Auth0ClientSecret {
  client_id: string;
  client_secret: string;
}

export type Auth0SecretMap = Map<string, Auth0ClientSecret>;

export type ClientIdMap = Partial<Record<string, string>>;

export interface Auth0Client {
  getSecrets: () => Promise<Auth0SecretMap>;
  getClientAccessToken: (clientId: string, clientSecret: string) => Promise<TokenResponse>;
}
