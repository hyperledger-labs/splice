import * as auth0 from '@pulumi/auth0';
import * as pulumi from '@pulumi/pulumi';
import { Auth0ClusterConfig, config } from 'cn-pulumi-common';

function newUiApp(
  resourceName: string,
  name: string,
  description: string,
  urlPrefixes: string[],
  // TODO(#12169) Make ingressName the same as the namespace (and rename this argument back to namespace)
  ingressName: string,
  clusterBasename: string,
  auth0DomainProvider: auth0.Provider
): auth0.Client {
  const urls = urlPrefixes
    .map(prefix => {
      return [
        `https://${prefix}.${ingressName}.${clusterBasename}.network.canton.global`,
        `https://${prefix}.${ingressName}.${clusterBasename}.global.canton.network.digitalasset.com`,
      ];
    })
    .flat();

  const ret = new auth0.Client(
    resourceName,
    {
      name: `${name} (Pulumi managed, ${clusterBasename})`,
      appType: 'spa',
      callbacks: urls,
      allowedOrigins: urls,
      allowedLogoutUrls: urls,
      webOrigins: urls,
      description: ` ** Managed by Pulumi, do not edit manually **\n${description}`,
    },
    { provider: auth0DomainProvider }
  );
  // Credentials for the app are not configured through the arguments passed to the Client
  // constructor, but through a separate resource. We set the app to no authentication
  // (otherwise the default configuration created by the Auth0 provider is to require a client secret).
  new auth0.ClientCredentials(
    `${resourceName}Credentials`,
    {
      clientId: ret.id,
      authenticationMethod: 'none',
    },
    { provider: auth0DomainProvider }
  );
  return ret;
}

function cnAuth0(clusterBasename: string) {
  const auth0Domain = 'canton-network-dev.us.auth0.com';
  const auth0MgtClientId = config.requireEnv('AUTH0_CN_MANAGEMENT_API_CLIENT_ID');
  const auth0MgtClientSecret = config.requireEnv('AUTH0_CN_MANAGEMENT_API_CLIENT_SECRET');

  const provider = new auth0.Provider('dev', {
    domain: auth0Domain,
    clientId: auth0MgtClientId,
    clientSecret: auth0MgtClientSecret,
  });

  const validator1UiApp = newUiApp(
    'validator1UiApp',
    'Validator1 UI',
    'Used for the Wallet, ANS and Splitwell UIs for the standalone Validator1',
    ['wallet', 'cns', 'splitwell'],
    'validator1',
    clusterBasename,
    provider
  );
  const splitwellUiApp = newUiApp(
    'SplitwellUiApp',
    'Splitwell UI',
    'Used for the Wallet, ANS and Splitwell UIs for the Splitwell validator',
    ['wallet', 'cns', 'splitwell'],
    'splitwell',
    clusterBasename,
    provider
  );
  const svUiApps = [...Array(16).keys()].map(i => {
    const sv = i + 1;
    const uiApp = newUiApp(
      `sv${sv}UiApp`,
      `SV${sv} UI`,
      `Used for the Wallet, ANS and SV UIs for SV${sv}`,
      ['wallet', 'cns', 'sv'],
      // TODO(#12169) Clean up this fun
      sv == 1 ? 'sv-2' : `sv-${sv}-eng`,
      clusterBasename,
      provider
    );
    return uiApp;
  });

  const constIds = {
    appToClientId: {
      validator1: 'cf0cZaTagQUN59C1HBL2udiIBdFh2CWq',
      splitwell: 'ekPlYxilradhEnpWdS80WfW63z1nHvKy',
      splitwell_validator: 'hqpZ6TP0wGyG2yYwhH6NLpuo0MpJMQZW',
      'sv-1': 'OBpJ9oTyOLuAKF0H2hhzdSFUICt0diIn',
      'sv-2': 'rv4bllgKWAiW9tBtdvURMdHW42MAXghz',
      'sv-3': 'SeG68w0ubtLQ1dEMDOs4YKPRTyMMdDLk',
      'sv-4': 'CqKgSbH54dqBT7V1JbnCxb6TfMN8I1cN',
      'sv-5': 'RSgbsze3cGHipLxhPGtGy7fqtYgyefTb',
      'sv-6': '3MO1BRMNqEiIntIM1YWwBRT1EPpKyGO6',
      'sv-7': '4imYa3E6Q5JPdLjZxHatRDtV1Wurq7pK',
      'sv-8': 'lQogWncLX7AIc2laUj8VVW6zwNJ169vR',
      'sv-9': 'GReLRFp7OQVDHmAhIyWlcnS7ZdWLdqhd',
      'sv-10': 'GReLRFp7OQVDHmAhIyWlcnS7ZdWLdqhd',
      'sv-11': 'ndIxuns8kZoObE7qN6M3IbtKSZ7RRO9B',
      'sv-12': 'qnYhBjBJ5LQu0pM5M6V8e3erQsadfew1',
      'sv-13': 'IA7BOrFhKvQ5AP9g8DxSTmO6pVT0oed3',
      'sv-14': 'cY4I4HCHgDj2mkxSSEwguFQGRFEjhnTq',
      'sv-15': 'hwKLKN5TWpaPjzuY52ubNVIRF8Onnzgk',
      'sv-16': '9pvoTvQIt2l1rzlNnaEZVsnNDFTOvt7W',
      sv1_validator: '7YEiu1ty0N6uWAjL8tCAWTNi7phr7tov',
      sv2_validator: '5N2kwYLOqrHtnnikBqw8A7foa01kui7h',
      sv3_validator: 'V0RjcwPCsIXqYTslkF5mjcJn70AiD0dh',
      sv4_validator: 'FqRozyrmu2d6dFQYC4J9uK8Y6SXCVrhL',
      sv5_validator: 'TdcDPsIwSXVw4rZmGqxl6Ifkn4neeOzW',
      sv6_validator: '4pUXGkvvybNyTeWXEBlesr9qcYCQh2sh',
      sv7_validator: '2cfFl6z5huY4rVYvxOEja8MvDdplYCDW',
      sv8_validator: 'JYvSRekV1E5EUZ2sJ494YyHXbxR3OHIR',
      sv9_validator: 'BABNqQ3m5ROTGJTlTHVlIckS3cwJ0M0w',
      sv10_validator: 'EKBJkDcOHosrnhLALfrQYG6Uc4Csqwbe',
      sv11_validator: '8jpCSqSkLxdY8zdmJwm0XXRfxFnPNAhG',
      sv12_validator: 'PEMwunsstamR1c5k3LdjVInTKlVTkeb6',
      sv13_validator: 'eqssDmClrmtQFTgJ7XIP7RDdhcD6iGfx',
      sv14_validator: 'luGkjf4AvM5PYhmi3X5rFmKLzxHTBlgz',
      sv15_validator: 'gL9Iv3iUiPTtDvyEZ9b4wCcTvz3G6qys',
      sv16_validator: '6ANtCorumVE8Ur7n1gJ8Gfvgv5pa96mZ',
    },

    appToApiAudience: {},

    appToClientAudience: {},

    fixedTokenCacheName: 'auth0-fixed-token-cache',

    auth0Domain: auth0Domain,
    auth0MgtClientId: auth0MgtClientId,
    auth0MgtClientSecret: '',
  };

  return pulumi
    .all([validator1UiApp.id, splitwellUiApp.id, svUiApps.map(uiApp => uiApp.id)])
    .apply(([validator1UiId, splitwellUiId, svUiIds]) => {
      return {
        ...constIds,
        namespaceToUiToClientId: {
          validator1: {
            wallet: validator1UiId,
            cns: validator1UiId,
            splitwell: validator1UiId,
          },
          splitwell: {
            wallet: splitwellUiId,
            cns: splitwellUiId,
            splitwell: splitwellUiId,
          },
          ...svUiIds.reduce(
            (o, key, idx) => ({
              ...o,
              [`sv-${idx + 1}`]: {
                wallet: key,
                cns: key,
                sv: key,
              },
            }),
            {}
          ),
        },
      };
    });
}

function svRunbookAuth0(clusterBasename: string) {
  const auth0Domain = 'canton-network-sv-test.us.auth0.com';
  const auth0MgtClientId = config.requireEnv('AUTH0_SV_MANAGEMENT_API_CLIENT_ID');
  const auth0MgtClientSecret = config.requireEnv('AUTH0_SV_MANAGEMENT_API_CLIENT_SECRET');

  const provider = new auth0.Provider('sv', {
    domain: auth0Domain,
    clientId: auth0MgtClientId,
    clientSecret: auth0MgtClientSecret,
  });

  const walletUiApp = newUiApp(
    'SvWalletUi',
    'Wallet UI',
    'Used for the Wallet UI for the SV runbook',
    ['wallet'],
    'sv',
    clusterBasename,
    provider
  );
  const ansUiApp = newUiApp(
    'SvCnsUi',
    'ANS UI',
    'Used for the ANS UI for the SV runbook',
    ['cns'],
    'sv',
    clusterBasename,
    provider
  );
  const svUiApp = newUiApp(
    'SvSvUi',
    'SV UI',
    'Used for the SV UI for the SV runbook',
    ['sv'],
    'sv',
    clusterBasename,
    provider
  );

  return pulumi
    .all([walletUiApp.id, ansUiApp.id, svUiApp.id])
    .apply(([walletUiAppId, ansUiAppId, svUiAppId]) => {
      return {
        appToClientId: {
          sv: 'bUfFRpl2tEfZBB7wzIo9iRNGTj8wMeIn',
          validator: 'uxeQGIBKueNDmugVs1RlMWEUZhZqyLyr',
        },

        namespaceToUiToClientId: {
          sv: {
            wallet: walletUiAppId,
            sv: svUiAppId,
            cns: ansUiAppId,
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

        auth0Domain: 'canton-network-sv-test.us.auth0.com',
        auth0MgtClientId: config.requireEnv('AUTH0_SV_MANAGEMENT_API_CLIENT_ID'),
        auth0MgtClientSecret: '',
      };
    });
}

function validatorRunbookAuth0(clusterBasename: string) {
  const auth0Domain = 'canton-network-validator-test.us.auth0.com';
  const auth0MgtClientId = config.requireEnv('AUTH0_VALIDATOR_MANAGEMENT_API_CLIENT_ID');
  const auth0MgtClientSecret = config.requireEnv('AUTH0_VALIDATOR_MANAGEMENT_API_CLIENT_SECRET');

  const provider = new auth0.Provider('validator', {
    domain: auth0Domain,
    clientId: auth0MgtClientId,
    clientSecret: auth0MgtClientSecret,
  });

  const walletUiApp = newUiApp(
    'validatorWalletUi',
    'Wallet UI',
    'Used for the Wallet UI for the validator runbook',
    ['wallet'],
    'validator',
    clusterBasename,
    provider
  );
  const ansUiApp = newUiApp(
    'validatorCnsUi',
    'ANS UI',
    'Used for the ANS UI for the validator runbook',
    ['cns'],
    'validator',
    clusterBasename,
    provider
  );

  return pulumi.all([walletUiApp.id, ansUiApp.id]).apply(([walletUiAppId, ansUiAppId]) => {
    return {
      appToClientId: {
        validator: 'cznBUeB70fnpfjaq9TzblwiwjkVyvh5z',
      },

      namespaceToUiToClientId: {
        validator: {
          wallet: walletUiAppId,
          cns: ansUiAppId,
        },
      },

      appToApiAudience: {
        participant: 'https://ledger_api.example.com', // The Ledger API in the validator-test tenant
        validator: 'https://validator.example.com/api', // The Validator App API in the validator-test tenant
      },

      appToClientAudience: {
        validator: 'https://ledger_api.example.com',
      },

      fixedTokenCacheName: 'auth0-fixed-token-cache-validator-test',

      auth0Domain: 'canton-network-validator-test.us.auth0.com',
      auth0MgtClientId: config.requireEnv('AUTH0_VALIDATOR_MANAGEMENT_API_CLIENT_ID'),
      auth0MgtClientSecret: '',
    };
  });
}

export function configureAuth0(clusterBasename: string): pulumi.Output<Auth0ClusterConfig> {
  const cnAuth0Cfg = cnAuth0(clusterBasename);
  const svRunbookAuth0Cfg = svRunbookAuth0(clusterBasename);
  const validatorRunbookAuth0Cfg = validatorRunbookAuth0(clusterBasename);
  return pulumi
    .all([cnAuth0Cfg, svRunbookAuth0Cfg, validatorRunbookAuth0Cfg])
    .apply(([cn, sv, validator]) => {
      const r: Auth0ClusterConfig = {
        cantonNetwork: cn,
        svRunbook: sv,
        validatorRunbook: validator,
      };
      return r;
    });
}
