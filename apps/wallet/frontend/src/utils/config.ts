import { Auth0ProviderOptions } from '@auth0/auth0-react';

// Configuration specified in files that are not part of this build.
// To use this external configuration, add a script file to the web site that is loaded
// before the application code, and writes the config to the global "window" variable.
const externalConfig = window.canton_network_config;

type RS256Auth = Auth0ProviderOptions;
type HS256UnsafeAuth = {
  secret: string;
};

export function isHs2456UnsafeAuthConfig(obj: unknown): obj is HS256UnsafeAuth {
  return typeof (obj as HS256UnsafeAuth).secret === 'string';
}

type AuthConfig = RS256Auth | HS256UnsafeAuth;

const getAuthConfig = (): AuthConfig => {
  if (process.env.REACT_APP_OAUTH_DOMAIN || externalConfig.auth.domain) {
    return {
      domain: process.env.REACT_APP_OAUTH_DOMAIN || externalConfig.auth.domain,
      clientId: process.env.REACT_APP_OAUTH_CLIENT_ID || externalConfig.auth.clientId,
      redirectUri: window.location.origin || externalConfig.auth.redirectUri,
    };
  }

  return {
    secret: process.env.REACT_APP_HMAC256_SECRET || externalConfig.auth.secret,
  };
};

export type Config = {
  auth: AuthConfig;
  wallet: {
    grpcUrl: string;
  };
  validator: {
    grpcUrl: string;
  };
  directory: {
    grpcUrl: string;
  };
};

export const config: Config = {
  auth: getAuthConfig(),
  wallet: {
    grpcUrl: process.env.REACT_APP_GRPC_URL || externalConfig.wallet.grpcUrl,
  },
  validator: {
    grpcUrl: process.env.REACT_APP_VALIDATOR_API_GRPC_URL || externalConfig.validator.grpcUrl,
  },
  directory: {
    grpcUrl: process.env.REACT_APP_DIRECTORY_API_GRPC_URL || externalConfig.directory.grpcUrl,
  },
};
