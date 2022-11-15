import { AuthProviderProps } from 'react-oidc-context';

// Configuration specified in files that are not part of this build.
// To use this external configuration, add a script file to the web site that is loaded
// before the application code, and writes the config to the global "window" variable.
const externalConfig = window.canton_network_config;

const enum Algorithm {
  RS256 = 'rs-256',
  HS256UNSAFE = 'hs-256-unsafe',
}

type RS256Auth = AuthProviderProps & {
  algorithm: Algorithm.RS256;
};

type HS256UnsafeAuth = {
  algorithm: Algorithm.HS256UNSAFE;
  secret: string;
};

type AuthConfig = RS256Auth | HS256UnsafeAuth;

export function isHs2456UnsafeAuthConfig(obj: AuthConfig): obj is HS256UnsafeAuth {
  return obj.algorithm === Algorithm.HS256UNSAFE;
}

const getAuthConfig = (): AuthConfig => {
  const algorithm = process.env.REACT_APP_AUTH_ALGORITHM || externalConfig.auth.algorithm;

  if (algorithm === Algorithm.RS256) {
    const authority = process.env.REACT_APP_AUTH_AUTHORITY || externalConfig.auth.authority;
    const client_id = process.env.REACT_APP_AUTH_CLIENT_ID || externalConfig.auth.client_id;
    const redirect_uri = externalConfig.auth.redirect_uri || window.location.origin;

    return { algorithm, authority, client_id, redirect_uri };
  } else if (algorithm === Algorithm.HS256UNSAFE) {
    const secret = process.env.REACT_APP_AUTH_SECRET || externalConfig.auth.secret;

    return { algorithm, secret };
  } else {
    throw new Error('Invalid or missing algorithm type specified: ' + algorithm);
  }
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
