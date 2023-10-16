import { Auth0Config, requireEnv } from 'cn-pulumi-common';

const auth0Account = 'canton-network-validator-test.us';

export const auth0Cfg: Auth0Config = {
  appToClientId: {
    validator: 'cznBUeB70fnpfjaq9TzblwiwjkVyvh5z',
  },

  // For the Validator runbook, we use dedicated auth0 applications for each UI app, so we don't use the namespaceToUiCliendId mapping
  namespaceToUiClientId: {},

  appToApiAudience: {
    participant: 'https://ledger_api.example.com', // The Ledger API in the validator-test tenant
    validator: 'https://validator.example.com/api', // The Validator App API in the validator-test tenant
  },

  appToClientAudience: {
    validator: 'https://ledger_api.example.com',
  },

  fixedTokenCacheName: 'auth0-fixed-token-cache-validator-test',

  // TODO(#5836): the naming we have for this vs those for canton-network tenant is terrible!!
  auth0Domain: `${auth0Account}.auth0.com`,
  auth0MgtClientId: requireEnv('AUTH0_VALIDATOR_MANAGEMENT_API_CLIENT_ID'),
  auth0MgtClientSecret: requireEnv('AUTH0_VALIDATOR_MANAGEMENT_API_CLIENT_SECRET'),
};
