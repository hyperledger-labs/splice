import { Auth0ProviderOptions } from '@auth0/auth0-react';

// Configuration specified in files that are not part of this build.
// To use this external configuration, add a script file to the web site that is loaded
// before the application code, and writes the config to the global "window" variable.
const externalConfig = window.canton_network_config;

const enum Algorithm {
  RS256 = 'rs-256',
  HS256UNSAFE = 'hs-256-unsafe',
}

type RS256Auth = Auth0ProviderOptions & {
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
    const domain = process.env.REACT_APP_AUTH_DOMAIN || externalConfig.auth.domain;
    const clientId = process.env.REACT_APP_AUTH_CLIENT_ID || externalConfig.auth.clientId;
    const redirectUri = externalConfig.auth.redirectUri || window.location.origin;

    return { algorithm, domain, clientId, redirectUri };
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
