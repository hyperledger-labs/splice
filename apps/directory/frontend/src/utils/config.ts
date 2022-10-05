import { Auth0ProviderOptions } from '@auth0/auth0-react';

// Configuration specified in files that are not part of this build.
// To use this external configuration, add a script file to the web site that is loaded
// before the application code, and writes the config to the global "window" variable.
const externalConfig = window.canton_network_config;

const authConfig: Auth0ProviderOptions = {
  domain: process.env.REACT_APP_OAUTH_DOMAIN || externalConfig.auth.domain,
  clientId: process.env.REACT_APP_OAUTH_CLIENT_ID || externalConfig.auth.clientId,
  redirectUri: window.location.origin || externalConfig.auth.redirectUri,
};

export type Config = {
  auth: Auth0ProviderOptions;
  directory: {
    grpcUrl: string;
  };
  ledgerApi: {
    grpcUrl: string;
  };
};

export const config: Config = {
  auth: authConfig,
  directory: {
    grpcUrl: process.env.REACT_APP_GRPC_URL || externalConfig.directory.grpcUrl,
  },
  ledgerApi: {
    grpcUrl: process.env.REACT_APP_LEDGER_API_GRPC_URL || externalConfig.ledgerApi.grpcUrl,
  },
};
