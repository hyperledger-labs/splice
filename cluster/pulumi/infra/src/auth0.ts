import * as auth0 from '@pulumi/auth0';
import * as pulumi from '@pulumi/pulumi';
import { Auth0ClusterConfig, requireEnv } from 'cn-pulumi-common';

function newUiApp(
  resourceName: string,
  name: string,
  description: string,
  urlPrefixes: string[],
  namespace: string,
  clusterBasename: string,
  auth0DomainProvider: auth0.Provider
): auth0.Client {
  const urls = urlPrefixes.map(prefix => {
    return `https://${prefix}.${namespace}.${clusterBasename}.network.canton.global`;
  });

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
  const auth0MgtClientId = requireEnv('AUTH0_CN_MANAGEMENT_API_CLIENT_ID');
  const auth0MgtClientSecret = requireEnv('AUTH0_CN_MANAGEMENT_API_CLIENT_SECRET');

  const provider = new auth0.Provider('dev', {
    domain: auth0Domain,
    clientId: auth0MgtClientId,
    clientSecret: auth0MgtClientSecret,
  });

  const validator1UiApp = newUiApp(
    'validator1UiApp',
    'Validator1 UI',
    'Used for the Wallet, CNS and Splitwell UIs for the standalone Validator1',
    ['wallet', 'cns', 'splitwell'],
    'validator1',
    clusterBasename,
    provider
  );
  const splitwellUiApp = newUiApp(
    'SplitwellUiApp',
    'Splitwell UI',
    'Used for the Wallet, CNS and Splitwell UIs for the Splitwell validator',
    ['wallet', 'cns', 'splitwell'],
    'splitwell',
    clusterBasename,
    provider
  );
  const sv1UiApp = newUiApp(
    'sv1UiApp',
    'SV1 Frontends',
    'Used for the Wallet, CNS and SV UIs for SV1',
    ['wallet', 'cns', 'sv'],
    'sv-1.svc',
    clusterBasename,
    provider
  );
  const sv2UiApp = newUiApp(
    'sv2UiApp',
    'SV2 Frontends',
    'Used for the Wallet, CNS and SV UIs for SV2',
    ['wallet', 'cns', 'sv'],
    'sv-2.svc',
    clusterBasename,
    provider
  );
  const sv3UiApp = newUiApp(
    'sv3UiApp',
    'SV3 Frontends',
    'Used for the Wallet, CNS and SV UIs for SV3',
    ['wallet', 'cns', 'sv'],
    'sv-3.svc',
    clusterBasename,
    provider
  );
  const sv4UiApp = newUiApp(
    'sv4UiApp',
    'SV4 Frontends',
    'Used for the Wallet, CNS and SV UIs for SV1',
    ['wallet', 'cns', 'sv'],
    'sv-4.svc',
    clusterBasename,
    provider
  );

  return pulumi
    .all([
      validator1UiApp.id,
      splitwellUiApp.id,
      sv1UiApp.id,
      sv2UiApp.id,
      sv3UiApp.id,
      sv4UiApp.id,
    ])
    .apply(([validator1UiId, splitwellUiId, sv1UiAppId, sv2UiAppId, sv3UiAppId, sv4UiAppId]) => {
      return {
        appToClientId: {
          validator1: 'cf0cZaTagQUN59C1HBL2udiIBdFh2CWq',
          splitwell: 'ekPlYxilradhEnpWdS80WfW63z1nHvKy',
          splitwell_validator: 'hqpZ6TP0wGyG2yYwhH6NLpuo0MpJMQZW',
          'sv-1': 'OBpJ9oTyOLuAKF0H2hhzdSFUICt0diIn',
          'sv-2': 'rv4bllgKWAiW9tBtdvURMdHW42MAXghz',
          'sv-3': 'SeG68w0ubtLQ1dEMDOs4YKPRTyMMdDLk',
          'sv-4': 'CqKgSbH54dqBT7V1JbnCxb6TfMN8I1cN',
          sv1_validator: '7YEiu1ty0N6uWAjL8tCAWTNi7phr7tov',
          sv2_validator: '5N2kwYLOqrHtnnikBqw8A7foa01kui7h',
          sv3_validator: 'V0RjcwPCsIXqYTslkF5mjcJn70AiD0dh',
          sv4_validator: 'FqRozyrmu2d6dFQYC4J9uK8Y6SXCVrhL',
        },

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
          'sv-1': {
            wallet: sv1UiAppId,
            cns: sv1UiAppId,
            sv: sv1UiAppId,
          },
          'sv-2': {
            wallet: sv2UiAppId,
            cns: sv2UiAppId,
            sv: sv2UiAppId,
          },
          'sv-3': {
            wallet: sv3UiAppId,
            cns: sv3UiAppId,
            sv: sv3UiAppId,
          },
          'sv-4': {
            wallet: sv4UiAppId,
            cns: sv4UiAppId,
            sv: sv4UiAppId,
          },
        },

        appToApiAudience: {},

        appToClientAudience: {},

        fixedTokenCacheName: 'auth0-fixed-token-cache',

        auth0Domain: auth0Domain,
        auth0MgtClientId: auth0MgtClientId,
        auth0MgtClientSecret: '',
      };
    });
}

function svRunbookAuth0(clusterBasename: string) {
  const auth0Domain = 'canton-network-sv-test.us.auth0.com';
  const auth0MgtClientId = requireEnv('AUTH0_SV_MANAGEMENT_API_CLIENT_ID');
  const auth0MgtClientSecret = requireEnv('AUTH0_SV_MANAGEMENT_API_CLIENT_SECRET');

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
    'sv.svc',
    clusterBasename,
    provider
  );
  const cnsUiApp = newUiApp(
    'SvCnsUi',
    'CNS UI',
    'Used for the CNS UI for the SV runbook',
    ['cns'],
    'sv.svc',
    clusterBasename,
    provider
  );
  const svUiApp = newUiApp(
    'SvSvUi',
    'SV UI',
    'Used for the SV UI for the SV runbook',
    ['sv'],
    'sv.svc',
    clusterBasename,
    provider
  );

  return pulumi
    .all([walletUiApp.id, cnsUiApp.id, svUiApp.id])
    .apply(([walletUiAppId, cnsUiAppId, svUiAppId]) => {
      return {
        appToClientId: {
          sv: 'bUfFRpl2tEfZBB7wzIo9iRNGTj8wMeIn',
          validator: 'uxeQGIBKueNDmugVs1RlMWEUZhZqyLyr',
        },

        namespaceToUiToClientId: {
          sv: {
            wallet: walletUiAppId,
            sv: svUiAppId,
            cns: cnsUiAppId,
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
        auth0MgtClientId: requireEnv('AUTH0_SV_MANAGEMENT_API_CLIENT_ID'),
        auth0MgtClientSecret: '',
      };
    });
}

function validatorRunbookAuth0(clusterBasename: string) {
  const auth0Domain = 'canton-network-validator-test.us.auth0.com';
  const auth0MgtClientId = requireEnv('AUTH0_VALIDATOR_MANAGEMENT_API_CLIENT_ID');
  const auth0MgtClientSecret = requireEnv('AUTH0_VALIDATOR_MANAGEMENT_API_CLIENT_SECRET');

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
  const cnsUiApp = newUiApp(
    'validatorCnsUi',
    'CNS UI',
    'Used for the CNS UI for the validator runbook',
    ['cns'],
    'validator',
    clusterBasename,
    provider
  );

  return pulumi.all([walletUiApp.id, cnsUiApp.id]).apply(([walletUiAppId, cnsUiAppId]) => {
    return {
      appToClientId: {
        validator: 'cznBUeB70fnpfjaq9TzblwiwjkVyvh5z',
      },

      namespaceToUiToClientId: {
        validator: {
          wallet: walletUiAppId,
          cns: cnsUiAppId,
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
      auth0MgtClientId: requireEnv('AUTH0_VALIDATOR_MANAGEMENT_API_CLIENT_ID'),
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
