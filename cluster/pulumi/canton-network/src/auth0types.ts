export interface Auth0ClientSecret {
  client_id: string;
  client_secret: string;
}

export type Auth0SecretMap = Map<string, Auth0ClientSecret>;

export interface Auth0Client {
  getSecrets: () => Promise<Auth0SecretMap>;
}
