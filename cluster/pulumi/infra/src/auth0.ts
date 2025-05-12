import * as auth0 from '@pulumi/auth0';
import * as pulumi from '@pulumi/pulumi';
import {
  Auth0ClusterConfig,
  NamespaceToClientIdMapMap,
  ansDomainPrefix,
  config,
  isMainNet,
} from 'splice-pulumi-common';

function newUiApp(
  resourceName: string,
  name: string,
  description: string,
  urlPrefixes: string[],
  // TODO(#12169) Make ingressName the same as the namespace (and rename this argument back to namespace)
  ingressName: string,
  clusterBasename: string,
  clusterDnsNames: string[],
  auth0DomainProvider: auth0.Provider,
  extraUrls: string[] = []
): auth0.Client {
  const urls = urlPrefixes
    .map(prefix => {
      return clusterDnsNames.map(dnsName => {
        return `https://${prefix}.${ingressName}.${dnsName}`;
      });
    })
    .flat()
    .concat(extraUrls);

  const ret = new auth0.Client(
    resourceName,
    {
      name: `${name} (Pulumi managed, ${clusterBasename})`,
      appType: 'spa',
      callbacks: urls,
      allowedOrigins: urls,
      allowedLogoutUrls: urls,
      webOrigins: urls,
      crossOriginAuth: false,
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

function spliceAuth0(clusterBasename: string, dnsNames: string[]) {
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
    ['wallet', ansDomainPrefix, 'splitwell'],
    'validator1',
    clusterBasename,
    dnsNames,
    provider
  );
  const splitwellUiApp = newUiApp(
    'SplitwellUiApp',
    'Splitwell UI',
    'Used for the Wallet, ANS and Splitwell UIs for the Splitwell validator',
    ['wallet', ansDomainPrefix, 'splitwell'],
    'splitwell',
    clusterBasename,
    dnsNames,
    provider
  );
  const svUiApps = [...Array(16).keys()].map(i => {
    const sv = i + 1;
    const uiApp = newUiApp(
      `sv${sv}UiApp`,
      `SV${sv} UI`,
      `Used for the Wallet, ANS and SV UIs for SV${sv}`,
      ['wallet', ansDomainPrefix, 'sv'],
      // TODO(#12169) Clean up this fun
      sv == 1 ? 'sv-2' : `sv-${sv}-eng`,
      clusterBasename,
      dnsNames,
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

function svRunbookAuth0(
  clusterBasename: string,
  dnsNames: string[],
  auth0ProviderName: string,
  auth0Domain: string,
  auth0MgtClientId: string,
  auth0MgtClientSecret: string,
  svDescription: string,
  namespace: string,
  ingressName: string,
  svBackendClientId: string,
  validatorBackendClientId: string,
  ledgerApiAudience: string,
  svApiAudience: string,
  validatorApiAudience: string,
  fixedTokenCacheName: string
) {
  const provider = new auth0.Provider(auth0ProviderName, {
    domain: auth0Domain,
    clientId: auth0MgtClientId,
    clientSecret: auth0MgtClientSecret,
  });

  const walletUiApp = newUiApp(
    'SvWalletUi',
    'Wallet UI',
    `Used for the Wallet UI for ${svDescription}`,
    ['wallet'],
    ingressName,
    clusterBasename,
    dnsNames,
    provider
  );
  const ansUiApp = newUiApp(
    'SvCnsUi',
    'ANS UI',
    `Used for the ANS UI for ${svDescription}`,
    [ansDomainPrefix],
    ingressName,
    clusterBasename,
    dnsNames,
    provider
  );
  const svUiApp = newUiApp(
    'SvSvUi',
    'SV UI',
    `Used for the SV UI for ${svDescription}`,
    ['sv'],
    ingressName,
    clusterBasename,
    dnsNames,
    provider
  );

  return pulumi
    .all([walletUiApp.id, ansUiApp.id, svUiApp.id])
    .apply(([walletUiAppId, ansUiAppId, svUiAppId]) => {
      const nsToUiToClientId: NamespaceToClientIdMapMap = {};
      nsToUiToClientId[namespace] = {
        wallet: walletUiAppId,
        sv: svUiAppId,
        cns: ansUiAppId,
      };

      return {
        appToClientId: {
          sv: svBackendClientId,
          validator: validatorBackendClientId,
        },

        namespaceToUiToClientId: nsToUiToClientId,

        appToApiAudience: {
          participant: ledgerApiAudience,
          sv: svApiAudience,
          validator: validatorApiAudience,
        },

        appToClientAudience: {
          sv: ledgerApiAudience,
          validator: ledgerApiAudience,
        },

        fixedTokenCacheName: fixedTokenCacheName,

        auth0Domain: auth0Domain,
        auth0MgtClientId: auth0MgtClientId,
        auth0MgtClientSecret: '',
      };
    });
}

function validatorRunbookAuth0(clusterBasename: string, dnsNames: string[]) {
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
    dnsNames,
    provider,
    ['http://localhost:3000', 'http://wallet.localhost']
  );
  const ansUiApp = newUiApp(
    'validatorCnsUi',
    'ANS UI',
    'Used for the ANS UI for the validator runbook',
    [ansDomainPrefix],
    'validator',
    clusterBasename,
    dnsNames,
    provider,
    ['http://localhost:3001', 'http://ans.localhost']
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

export function configureAuth0(
  clusterBasename: string,
  dnsNames: string[]
): pulumi.Output<Auth0ClusterConfig> {
  if (isMainNet) {
    const auth0Cfg = svRunbookAuth0(
      clusterBasename,
      dnsNames,
      'main',
      'canton-network-mainnet.us.auth0.com',
      config.requireEnv('AUTH0_MAIN_MANAGEMENT_API_CLIENT_ID'),
      config.requireEnv('AUTH0_MAIN_MANAGEMENT_API_CLIENT_SECRET'),
      'sv-1 (Digital-Asset 2)',
      'sv-1',
      'sv-2', // Ingress name of sv-1 is sv-2!
      'pC5Dw7qDWDfNREKgLwx2Vpz2Ns7j3cRK',
      'B4Ir9KiFqiCOHCpSDiPJN6PzkjKjDsbR',
      'https://ledger_api.main.digitalasset.com',
      'https://sv.main.digitalasset.com',
      'https://validator.main.digitalasset.com',
      'DO_NOT_USE'
    );
    return auth0Cfg.apply(mainnetCfg => {
      const r: Auth0ClusterConfig = {
        mainnet: mainnetCfg,
      };
      return r;
    });
  } else {
    const spliceAuth0Cfg = spliceAuth0(clusterBasename, dnsNames);
    const svRunbookAuth0Cfg = svRunbookAuth0(
      clusterBasename,
      dnsNames,
      'sv',
      'canton-network-sv-test.us.auth0.com',
      config.requireEnv('AUTH0_SV_MANAGEMENT_API_CLIENT_ID'),
      config.requireEnv('AUTH0_SV_MANAGEMENT_API_CLIENT_SECRET'),
      'the SV runbook',
      'sv',
      'sv',
      'bUfFRpl2tEfZBB7wzIo9iRNGTj8wMeIn',
      'uxeQGIBKueNDmugVs1RlMWEUZhZqyLyr',
      'https://ledger_api.example.com',
      'https://sv.example.com/api',
      'https://validator.example.com/api',
      'auth0-fixed-token-cache-sv-test'
    );
    const validatorRunbookAuth0Cfg = validatorRunbookAuth0(clusterBasename, dnsNames);
    return pulumi
      .all([spliceAuth0Cfg, svRunbookAuth0Cfg, validatorRunbookAuth0Cfg])
      .apply(([splice, sv, validator]) => {
        const r: Auth0ClusterConfig = {
          cantonNetwork: splice,
          svRunbook: sv,
          validatorRunbook: validator,
        };
        return r;
      });
  }
}
