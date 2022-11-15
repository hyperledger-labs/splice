import { AuthProviderProps } from 'react-oidc-context';

// Configuration specified in files that are not part of this build.
// To use this external configuration, add a script file to the web site that is loaded
// before the application code, and writes the config to the global "window" variable.
const externalConfig = window.canton_network_config;

const authConfig: AuthProviderProps = {
  authority: process.env.REACT_APP_AUTH_AUTHORITY || externalConfig.auth.authority,
  client_id: process.env.REACT_APP_AUTH_CLIENT_ID || externalConfig.auth.client_id,
  redirect_uri: window.location.origin || externalConfig.auth.redirectUri,
};

export type Config = {
  auth: AuthProviderProps;
  wallet: {
    uiUrl: string;
  };
  splitwise: {
    grpcUrl: string;
  };
  ledgerApi: {
    grpcUrl: string;
  };
  directory: {
    grpcUrl: string;
  };
  scan: {
    grpcUrl: string;
  };
};

export const config: Config = {
  auth: authConfig,
  wallet: {
    uiUrl: process.env.REACT_APP_WALLET_UI_URL || externalConfig.wallet.uiUrl,
  },
  splitwise: {
    grpcUrl: process.env.REACT_APP_GRPC_URL || externalConfig.splitwise.grpcUrl,
  },
  ledgerApi: {
    grpcUrl: process.env.REACT_APP_LEDGER_API_GRPC_URL || externalConfig.ledgerApi.grpcUrl,
  },
  directory: {
    grpcUrl: process.env.REACT_APP_DIRECTORY_API_GRPC_URL || externalConfig.directory.grpcUrl,
  },
  scan: {
    grpcUrl: process.env.REACT_APP_SCAN_API_GRPC_URL || externalConfig.scan.grpcUrl,
  },
};
