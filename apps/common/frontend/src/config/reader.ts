import { z } from 'zod';

import { baseConfigSchema } from './schema';
import { Algorithm, authSchema } from './schema/auth';

// Configuration specified in files that are not part of this build.
// To use this external configuration, add a script file to the web site that is loaded
// before the application code, and writes the config to the global "window" variable.
const externalConfig = window.canton_network_config;

// Environment var overrides: all renamed here for 1) brevity, 2) having a central list of all valid vars
const ENV_AUTH_ALGORITHM = process.env.REACT_APP_AUTH_ALGORITHM;
const ENV_AUTH_SECRET = process.env.REACT_APP_AUTH_SECRET;
const ENV_AUTH_AUTHORITY = process.env.REACT_APP_AUTH_AUTHORITY;
const ENV_AUTH_CLIENT_ID = process.env.REACT_APP_AUTH_CLIENT_ID;
const ENV_AUTH_TOKEN_AUDIENCE = process.env.REACT_APP_AUTH_TOKEN_AUDIENCE;
const ENV_AUTH_TOKEN_SCOPE = process.env.REACT_APP_AUTH_TOKEN_SCOPE;

const ENV_TESTAUTH_SECRET = process.env.REACT_APP_TESTAUTH_SECRET;
const ENV_TESTAUTH_TOKEN_AUDIENCE = process.env.REACT_APP_TESTAUTH_TOKEN_AUDIENCE;
const ENV_TESTAUTH_TOKEN_SCOPE = process.env.REACT_APP_TESTAUTH_TOKEN_SCOPE;

const ENV_SERVICE_WALLET_GRPC_URL = process.env.REACT_APP_SERVICE_WALLET_GRPC_URL;
const ENV_SERVICE_WALLET_UI_URL = process.env.REACT_APP_SERVICE_WALLET_UI_URL;
const ENV_SERVICE_VALIDATOR_GRPC_URL = process.env.REACT_APP_SERVICE_VALIDATOR_GRPC_URL;
const ENV_SERVICE_SCAN_GRPC_URL = process.env.REACT_APP_SERVICE_SCAN_GRPC_URL;
const ENV_SERVICE_LEDGER_API_GRPC_URL = process.env.REACT_APP_SERVICE_LEDGER_API_GRPC_URL;

const loadRs256Config = (externalConf: z.infer<typeof authSchema>, algorithm: Algorithm.RS256) => {
  let authority = ENV_AUTH_AUTHORITY;
  let client_id = ENV_AUTH_CLIENT_ID;

  let token_audience = ENV_AUTH_TOKEN_AUDIENCE || externalConf.token_audience;
  let token_scope = ENV_AUTH_TOKEN_SCOPE || externalConf.token_scope;

  // If external config is configured the same as the override, merge fallback values
  if (externalConf.algorithm === algorithm) {
    authority = authority || externalConf.authority;
    client_id = client_id || externalConf.client_id;
  }

  return { algorithm, authority, client_id, token_audience, token_scope };
};

const loadHs256UnsafeConfig = (
  externalConf: z.infer<typeof authSchema>,
  algorithm: Algorithm.HS256UNSAFE
) => {
  let secret = ENV_AUTH_SECRET;
  let token_audience = ENV_AUTH_TOKEN_AUDIENCE || externalConf.token_audience;
  let token_scope = ENV_AUTH_TOKEN_SCOPE || externalConf.token_scope;

  // If external config is configured the same as the override, merge fallback values
  if (externalConf.algorithm === algorithm) {
    secret = secret || externalConf.secret;
  }

  return { algorithm, secret, token_audience, token_scope };
};

const loadAuthConfig = () => {
  const externalAuthConfig = authSchema.parse(externalConfig.auth);

  // Handle env var overrides
  switch (ENV_AUTH_ALGORITHM) {
    case Algorithm.RS256:
      return loadRs256Config(externalAuthConfig, Algorithm.RS256);
    case Algorithm.HS256UNSAFE:
      return loadHs256UnsafeConfig(externalAuthConfig, Algorithm.HS256UNSAFE);
    default:
      return externalConfig.auth;
  }
};

const loadTestAuthConfig = () => {
  const externalTestAuthConfig = externalConfig.testAuth;
  const token_audience = ENV_TESTAUTH_TOKEN_AUDIENCE || externalTestAuthConfig?.token_audience;
  const token_scope = ENV_TESTAUTH_TOKEN_SCOPE || externalTestAuthConfig?.token_scope;

  if (ENV_TESTAUTH_SECRET) {
    return { secret: ENV_TESTAUTH_SECRET, token_audience, token_scope };
  } else {
    return externalTestAuthConfig;
  }
};

const overrideServiceKey = (
  conf: { services: { [k: string]: { [k: string]: string } } },
  service: string,
  key: string,
  override?: string
) => {
  if (override) {
    if (typeof conf.services[service] === 'undefined') {
      conf.services[service] = {};
    }
    conf.services[service][key] = override;
  }
};

const loadServicesConfig = () => {
  const conf = { ...externalConfig };

  // Handle env var overrides
  overrideServiceKey(conf, 'wallet', 'grpcUrl', ENV_SERVICE_WALLET_GRPC_URL);
  overrideServiceKey(conf, 'wallet', 'uiUrl', ENV_SERVICE_WALLET_UI_URL);
  overrideServiceKey(conf, 'validator', 'grpcUrl', ENV_SERVICE_VALIDATOR_GRPC_URL);
  overrideServiceKey(conf, 'scan', 'grpcUrl', ENV_SERVICE_SCAN_GRPC_URL);
  overrideServiceKey(conf, 'ledgerApi', 'grpcUrl', ENV_SERVICE_LEDGER_API_GRPC_URL);

  return conf.services;
};

export class ConfigReader<
  A extends z.ZodRawShape,
  B extends z.ZodTypeAny,
  T extends z.ZodObject<A, 'strip', B>
> {
  schema;

  constructor(schema?: T) {
    if (schema) {
      this.schema = schema.strict();
    } else {
      this.schema = baseConfigSchema.strict();
    }
  }

  // I can't for the life of me figure out the explicit type returned by parse(),
  // and the implicit typing checks out just fine in this case
  // eslint-disable-next-line @typescript-eslint/explicit-module-boundary-types
  loadConfig() {
    const config = {
      auth: loadAuthConfig(),
      testAuth: loadTestAuthConfig(),
      services: loadServicesConfig(),
    };

    return this.schema.parse(config);
  }
}
