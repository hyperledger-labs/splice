import { AuthProviderProps } from 'react-oidc-context';

// Configuration specified in files that are not part of this build.
// To use this external configuration, add a script file to the web site that is loaded
// before the application code, and writes the config to the global "window" variable.
const externalConfig = window.canton_network_config;

// TODO(#1445) Reduce duplication with wallet frontend; move common parts to common

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

type TestAuthConfig = {
  // algorithm is implicit
  secret: string;
};

export function isHs2456UnsafeAuthConfig(obj: AuthConfig): obj is HS256UnsafeAuth {
  return obj.algorithm === Algorithm.HS256UNSAFE;
}

const getAuthConfig = (): AuthConfig => {
  const algorithm = process.env.REACT_APP_AUTH_ALGORITHM || externalConfig.auth.algorithm;

  if (algorithm === Algorithm.RS256) {
    const authority = process.env.REACT_APP_AUTH_AUTHORITY || externalConfig.auth.authority;
    const client_id = process.env.REACT_APP_AUTH_CLIENT_ID || externalConfig.auth.client_id;
    const redirect_uri = externalConfig.auth.redirect_uri || window.location.origin;

    // We need this for our auth0 test tenant setup so auth0 gives us the correct access token.
    // Note that we don't request the openid scope here, which means we'll only get the access token.
    // This is mainly a workaround for the fact that Canton can't parse JTWs with multiple audiences
    // (auth0 adds the "../userinfo" audience if we request the openid scope).
    const scope = 'daml_ledger_api';
    // TODO(#1836) Pick a future-proof audience that doesn't depend on a modified canton instance.
    const extraQueryParams = { audience: 'https://canton.network.global' };

    return { algorithm, authority, client_id, redirect_uri, scope, extraQueryParams };
  } else if (algorithm === Algorithm.HS256UNSAFE) {
    const secret = process.env.REACT_APP_AUTH_SECRET || externalConfig.auth.secret;

    return { algorithm, secret };
  } else {
    throw new Error('Invalid or missing algorithm type specified: ' + algorithm);
  }
};

const getTestAuthConfig = (): TestAuthConfig | undefined => {
  const secret = process.env.REACT_APP_TEST_AUTH_SECRET;
  if (secret) return { secret };
};

export type Config = {
  auth: AuthConfig;
  testAuth?: TestAuthConfig;
  directory: {
    grpcUrl: string;
  };
  ledgerApi: {
    grpcUrl: string;
  };
  wallet: {
    uiUrl: string;
  };
};

export const config: Config = {
  auth: getAuthConfig(),
  testAuth: getTestAuthConfig(),
  directory: {
    grpcUrl: process.env.REACT_APP_GRPC_URL || externalConfig.directory.grpcUrl,
  },
  ledgerApi: {
    grpcUrl: process.env.REACT_APP_LEDGER_API_GRPC_URL || externalConfig.ledgerApi.grpcUrl,
  },
  wallet: {
    uiUrl: process.env.REACT_APP_WALLET_UI_URL || externalConfig.wallet.uiUrl,
  },
};
