import { Auth0Config, requireEnv } from 'cn-pulumi-common';

const auth0Account = 'canton-network-sv-test.us';

export const auth0Cfg: Auth0Config = {
  appToClientId: {
    sv: 'bUfFRpl2tEfZBB7wzIo9iRNGTj8wMeIn',
    validator: 'uxeQGIBKueNDmugVs1RlMWEUZhZqyLyr',
  },

  namespaceToUiToClientId: {
    sv: {
      wallet: 'l9MS11POtbvPaVvgzns3Tdj9IDnosLwl',
      sv: '8S8o4U6OYWWuw5vPCIpFQGzzWM2IpHkx',
      cns: 'iwZgud30aDMMUYpZc5caSnjNATWwITzp',
    },
  },

  appToApiAudience: {
    participant: 'https://ledger_api.example.com', // The Ledger API in the sv-test tenant
    sv: 'https://sv.example.com/api', // The SV App API in the sv-test tenant
    validator: 'https://validator.example.com/api', // The Validator App API in the sv-test tenant
  },

  appToClientAudience: {
    sv: 'https://ledger_api.example.com',
    validator: 'https://ledger_api.example.com',
  },

  fixedTokenCacheName: 'auth0-fixed-token-cache-sv-test',

  // TODO(#5836): the naming we have for this vs those for canton-network tenant is terrible!!
  auth0Domain: `${auth0Account}.auth0.com`,
  auth0MgtClientId: requireEnv('AUTH0_SV_MANAGEMENT_API_CLIENT_ID'),
  auth0MgtClientSecret: requireEnv('AUTH0_SV_MANAGEMENT_API_CLIENT_SECRET'),
};
