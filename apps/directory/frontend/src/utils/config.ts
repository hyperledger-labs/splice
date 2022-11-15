import { AuthProviderProps } from 'react-oidc-context';

// Configuration specified in files that are not part of this build.
// To use this external configuration, add a script file to the web site that is loaded
// before the application code, and writes the config to the global "window" variable.
const externalConfig = window.canton_network_config;

const authConfig: AuthProviderProps = {
  authority: process.env.REACT_APP_AUTH_AUTHORITY || externalConfig.auth.authority,
  client_id: process.env.REACT_APP_AUTH_CLIENT_ID || externalConfig.auth.client_id,
  redirect_uri: window.location.origin || externalConfig.auth.redirect_uri,
};

export type Config = {
  auth: AuthProviderProps;
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
