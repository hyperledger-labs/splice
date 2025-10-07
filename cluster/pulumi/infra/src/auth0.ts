// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as auth0 from '@pulumi/auth0';
import * as pulumi from '@pulumi/pulumi';
import {
  ansDomainPrefix,
  AudienceMap,
  Auth0Config,
  Auth0ClusterConfig,
  ClientIdMap,
  config,
  isMainNet,
  NamespaceToClientIdMapMap,
  clusterProdLike,
} from '@lfdecentralizedtrust/splice-pulumi-common';
import { standardSvConfigs, extraSvConfigs } from '@lfdecentralizedtrust/splice-pulumi-common-sv';

function ledgerApiAudience(
  svNamespaces: string,
  clusterBasename: string,
  auth0DomainProvider: auth0.Provider
): pulumi.Output<string> {
  if (clusterProdLike) {
    // On prod clusters, we create a ledger API per SV namespace
    const auth0Api = new auth0.ResourceServer(
      `LedgerApi${svNamespaces.replace(/-/g, '')}`,
      {
        name: `Ledger API for SV ${svNamespaces} on ${clusterBasename} (Pulumi managed)`,
        identifier: `https://ledger_api.${svNamespaces}.${clusterBasename}.canton.network`,
        allowOfflineAccess: true,
      },
      { provider: auth0DomainProvider }
    );

    new auth0.ResourceServerScopes(
      `LedgerApiScopes${svNamespaces.replace(/-/g, '')}`,
      {
        resourceServerIdentifier: auth0Api.identifier,
        scopes: [
          {
            name: 'daml_ledger_api',
            description: 'Access to the Ledger API',
          },
        ],
      },
      { provider: auth0DomainProvider }
    );

    return auth0Api.identifier;
  } else {
    // On non-prod clusters, we currently use the hard-coded identifier that matches our docs, and the manually created auth0 API
    return pulumi.output('https://canton.network.global');
  }
}

function svAppAudience(
  svNamespaces: string,
  clusterBasename: string,
  auth0DomainProvider: auth0.Provider
): pulumi.Output<string> {
  if (clusterProdLike) {
    // On prod clusters, we create a SV App API per SV namespace
    const auth0Api = new auth0.ResourceServer(
      `SvAppApi${svNamespaces.replace(/-/g, '')}`,
      {
        name: `SV App API for SV ${svNamespaces} on ${clusterBasename} (Pulumi managed)`,
        identifier: `https://sv.${svNamespaces}.${clusterBasename}.canton.network/api`,
        allowOfflineAccess: true,
      },
      { provider: auth0DomainProvider }
    );

    return auth0Api.identifier;
  } else {
    // On non-prod clusters, we currently use the hard-coded identifier that matches our docs, and the manually created auth0 API (same one as ledger API)
    return pulumi.output('https://canton.network.global');
  }
}

function validatorAppAudience(
  svNamespaces: string,
  clusterBasename: string,
  auth0DomainProvider: auth0.Provider
): pulumi.Output<string> {
  if (clusterProdLike) {
    // On prod clusters, we create a Validator App API per SV namespace
    const auth0Api = new auth0.ResourceServer(
      `ValidatorAppApi${svNamespaces.replace(/-/g, '')}`,
      {
        name: `Validator App API for SV ${svNamespaces} on ${clusterBasename} (Pulumi managed)`,
        identifier: `https://validator.${svNamespaces}.${clusterBasename}.canton.network/api`,
        allowOfflineAccess: true,
      },
      { provider: auth0DomainProvider }
    );

    return auth0Api.identifier;
  } else {
    // On non-prod clusters, we currently use the hard-coded identifier that matches our docs, and the manually created auth0 API (same one as ledger API)
    return pulumi.output('https://canton.network.global');
  }
}

function newM2MApp(
  resourceName: string,
  name: string,
  description: string,
  clusterBasename: string,
  ledgerApiAud: pulumi.Output<string>,
  appAud: pulumi.Output<string>,
  auth0DomainProvider: auth0.Provider
): auth0.Client {
  const ret = new auth0.Client(
    resourceName,
    {
      name: `${name} (Pulumi managed, ${clusterBasename})`,
      appType: 'non_interactive',
      description: ` ** Managed by Pulumi, do not edit manually **\n${description}`,
    },
    { provider: auth0DomainProvider }
  );

  pulumi.all([ledgerApiAud, appAud]).apply(([ledgerApiAudValue, appAudValue]) => {
    new auth0.ClientGrant(
      `${resourceName}LedgerGrant`,
      {
        clientId: ret.id,
        audience: ledgerApiAudValue,
        scopes: ['daml_ledger_api'],
      },
      {
        provider: auth0DomainProvider,
      }
    );

    if (ledgerApiAudValue !== appAudValue) {
      new auth0.ClientGrant(
        `${resourceName}AppGrant`,
        {
          clientId: ret.id,
          audience: appAudValue,
          scopes: [],
        },
        {
          provider: auth0DomainProvider,
        }
      );
    }
  });

  return ret;
}

function newUiApp(
  resourceName: string,
  name: string,
  description: string,
  urlPrefixes: string[],
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

interface BackendAuth0Params {
  name: string;
  clientId: string;
}
interface svAuth0Params {
  namespace: string;
  description: string;
  ingressName: string;
  // if these are not provided we rely on the client ID being added separately
  svBackend?: BackendAuth0Params;
  validatorBackend?: BackendAuth0Params;
}
interface ApiAudienceAuth0Params {
  ledger: string;
  sv: string;
  validator: string;
}
function svsOnlyAuth0(
  clusterBasename: string,
  dnsNames: string[],
  provider: auth0.Provider,
  svs: svAuth0Params[],
  auth0Domain: string,
  auth0MgtClientId: string,
  fixedTokenCacheName: string,
  // only "same audiences for all" supported for now
  apiAudiences?: ApiAudienceAuth0Params
): pulumi.Output<Auth0Config> {
  const svUis = svs.map(sv =>
    newUiApp(
      `${sv.namespace.replace(/-/g, '')}UiApp`,
      `${sv.namespace.replace(/-/g, '').toUpperCase()} UI`,
      `Used for the Wallet, ANS and SV UIs for ${sv.description}`,
      ['wallet', ansDomainPrefix, 'sv'],
      sv.ingressName,
      clusterBasename,
      dnsNames,
      provider
    ).id.apply(appId => ({ ns: sv.namespace, id: appId }))
  );

  const nsToUiToCLientIdOutput: pulumi.Output<NamespaceToClientIdMapMap> = pulumi
    .all(svUis)
    .apply(uis =>
      uis.reduce(
        (acc, ui) => ({
          ...acc,
          [ui.ns]: {
            wallet: ui.id,
            sv: ui.id,
            cns: ui.id,
          },
        }),
        {} as NamespaceToClientIdMapMap
      )
    );

  const appToClientId: ClientIdMap = svs.reduce((acc, sv) => {
    const ledgerApiAud = ledgerApiAudience(sv.namespace, clusterBasename, provider);
    const svAppAud = svAppAudience(sv.namespace, clusterBasename, provider);
    const validatorAppAud = validatorAppAudience(sv.namespace, clusterBasename, provider);
    const svApp = newM2MApp(
      `${sv.namespace.replace(/-/g, '')}SvBackendApp`,
      `${sv.namespace.replace(/-/g, '').toUpperCase()} SV Backend`,
      `Used for the SV backend for ${sv.description} on ${clusterBasename}`,
      clusterBasename,
      ledgerApiAud,
      svAppAud,
      provider
    );
    const validatorApp = newM2MApp(
      `${sv.namespace.replace(/-/g, '')}ValidatorBackendApp`,
      `${sv.namespace.replace(/-/g, '').toUpperCase()} Validator Backend`,
      `Used for the Validator backend for ${sv.description} on ${clusterBasename}`,
      clusterBasename,
      ledgerApiAud,
      validatorAppAud,
      provider
    );
    const validatorAppName =
      sv.namespace == 'sv-da-1'
        ? 'sv-da-1_validator'
        : sv.namespace.replace('-', '') + '_validator';
    return {
      ...acc,
      ...{ [sv.namespace]: svApp.clientId, [validatorAppName]: validatorApp.clientId }, // FIXME: consider cleaning up that naming inconsistency of sv-1 vs sv1_validator, and the special case of sv-da-1 above
    };
  }, {});

  return nsToUiToCLientIdOutput.apply(nsToUiToClientId => {
    return {
      appToClientId: appToClientId,

      namespaceToUiToClientId: nsToUiToClientId,

      appToApiAudience: apiAudiences
        ? ({
            participant: apiAudiences.ledger,
            sv: apiAudiences.sv,
            validator: apiAudiences.validator,
          } as AudienceMap)
        : {},

      appToClientAudience: apiAudiences
        ? ({
            sv: apiAudiences.ledger,
            validator: apiAudiences.ledger,
          } as AudienceMap)
        : {},

      fixedTokenCacheName: fixedTokenCacheName,

      auth0Domain: auth0Domain,
      auth0MgtClientId: auth0MgtClientId,
      // TODO(tech-debt) We don't seem to set this anywhere?
      auth0MgtClientSecret: '',
    };
  });
}

function mainNetAuth0(clusterBasename: string, dnsNames: string[]): pulumi.Output<Auth0Config> {
  const auth0Domain = 'canton-network-mainnet.us.auth0.com';
  const auth0MgtClientId = config.requireEnv('AUTH0_MAIN_MANAGEMENT_API_CLIENT_ID');
  const auth0MgtClientSecret = config.requireEnv('AUTH0_MAIN_MANAGEMENT_API_CLIENT_SECRET');

  const provider = new auth0.Provider('main', {
    domain: auth0Domain,
    clientId: auth0MgtClientId,
    clientSecret: auth0MgtClientSecret,
  });

  // hardcoded sv1 will be removed once we switch DA-2 to KMS (and, likely, the sv-da-1 namespace)
  const sv1 = {
    namespace: 'sv-1',
    description: 'sv-1 (Digital-Asset 2)',
    ingressName: 'sv-2', // Ingress name of sv-1 is sv-2!
    svBackend: {
      name: 'sv',
      clientId: 'pC5Dw7qDWDfNREKgLwx2Vpz2Ns7j3cRK',
    },
    validatorBackend: {
      name: 'validator',
      clientId: 'B4Ir9KiFqiCOHCpSDiPJN6PzkjKjDsbR',
    },
  };

  const extraSvs: svAuth0Params[] = extraSvConfigs.map(sv => ({
    namespace: sv.nodeName,
    description: sv.onboardingName,
    ingressName: sv.ingressName,
    svBackend: {
      name: sv.auth0SvAppName,
      clientId: sv.auth0SvAppClientId!,
    },
    validatorBackend: {
      name: sv.auth0ValidatorAppName,
      clientId: sv.auth0ValidatorAppClientId!,
    },
  }));

  return svsOnlyAuth0(
    clusterBasename,
    dnsNames,
    provider,
    [sv1, ...extraSvs],
    auth0Domain,
    auth0MgtClientId,
    'DO_NOT_USE',
    {
      ledger: 'https://ledger_api.main.digitalasset.com',
      sv: 'https://sv.main.digitalasset.com',
      validator: 'https://validator.main.digitalasset.com',
    }
  );
}

function nonMainNetAuth0(clusterBasename: string, dnsNames: string[]): pulumi.Output<Auth0Config> {
  const auth0Domain = 'canton-network-dev.us.auth0.com';
  const auth0MgtClientId = config.requireEnv('AUTH0_CN_MANAGEMENT_API_CLIENT_ID');
  const auth0MgtClientSecret = config.requireEnv('AUTH0_CN_MANAGEMENT_API_CLIENT_SECRET');

  const provider = new auth0.Provider('dev', {
    domain: auth0Domain,
    clientId: auth0MgtClientId,
    clientSecret: auth0MgtClientSecret,
  });

  const standardSvs: svAuth0Params[] = standardSvConfigs.map(sv => ({
    namespace: sv.nodeName,
    description: sv.nodeName.replace(/-/g, '').toUpperCase(),
    ingressName: sv.ingressName,
    svBackend: sv.auth0SvAppClientId
      ? {
          name: sv.auth0SvAppName,
          clientId: sv.auth0SvAppClientId,
        }
      : undefined,
    validatorBackend: sv.auth0ValidatorAppClientId
      ? {
          name: sv.auth0ValidatorAppName,
          clientId: sv.auth0ValidatorAppClientId,
        }
      : undefined,
  }));
  const extraSvs: svAuth0Params[] = extraSvConfigs.map(sv => ({
    namespace: sv.nodeName,
    description: sv.onboardingName,
    ingressName: sv.ingressName,
    svBackend: {
      name: sv.auth0SvAppName,
      clientId: sv.auth0SvAppClientId!,
    },
    validatorBackend: {
      name: sv.auth0ValidatorAppName,
      clientId: sv.auth0ValidatorAppClientId!,
    },
  }));

  const baseAuth0 = svsOnlyAuth0(
    clusterBasename,
    dnsNames,
    provider,
    [...standardSvs, ...extraSvs],
    auth0Domain,
    auth0MgtClientId,
    'auth0-fixed-token-cache'
  );

  // hardcoded client IDs
  // TODO(tech-debt) consider folding into main config or into `config.yaml`
  const extraAppToClientIds: ClientIdMap = {
    validator1: 'cf0cZaTagQUN59C1HBL2udiIBdFh2CWq',
    splitwell: 'ekPlYxilradhEnpWdS80WfW63z1nHvKy',
    splitwell_validator: 'hqpZ6TP0wGyG2yYwhH6NLpuo0MpJMQZW',
    // 'sv-1': 'OBpJ9oTyOLuAKF0H2hhzdSFUICt0diIn',
    // 'sv-2': 'rv4bllgKWAiW9tBtdvURMdHW42MAXghz',
    // 'sv-3': 'SeG68w0ubtLQ1dEMDOs4YKPRTyMMdDLk',
    // 'sv-4': 'CqKgSbH54dqBT7V1JbnCxb6TfMN8I1cN',
    // 'sv-5': 'RSgbsze3cGHipLxhPGtGy7fqtYgyefTb',
    // 'sv-6': '3MO1BRMNqEiIntIM1YWwBRT1EPpKyGO6',
    // 'sv-7': '4imYa3E6Q5JPdLjZxHatRDtV1Wurq7pK',
    // 'sv-8': 'lQogWncLX7AIc2laUj8VVW6zwNJ169vR',
    // 'sv-9': 'GReLRFp7OQVDHmAhIyWlcnS7ZdWLdqhd',
    // 'sv-10': 'GReLRFp7OQVDHmAhIyWlcnS7ZdWLdqhd',
    // 'sv-11': 'ndIxuns8kZoObE7qN6M3IbtKSZ7RRO9B',
    // 'sv-12': 'qnYhBjBJ5LQu0pM5M6V8e3erQsadfew1',
    // 'sv-13': 'IA7BOrFhKvQ5AP9g8DxSTmO6pVT0oed3',
    // 'sv-14': 'cY4I4HCHgDj2mkxSSEwguFQGRFEjhnTq',
    // 'sv-15': 'hwKLKN5TWpaPjzuY52ubNVIRF8Onnzgk',
    // 'sv-16': '9pvoTvQIt2l1rzlNnaEZVsnNDFTOvt7W',
    // sv1_validator: '7YEiu1ty0N6uWAjL8tCAWTNi7phr7tov',
    // sv2_validator: '5N2kwYLOqrHtnnikBqw8A7foa01kui7h',
    // sv3_validator: 'V0RjcwPCsIXqYTslkF5mjcJn70AiD0dh',
    // sv4_validator: 'FqRozyrmu2d6dFQYC4J9uK8Y6SXCVrhL',
    // sv5_validator: 'TdcDPsIwSXVw4rZmGqxl6Ifkn4neeOzW',
    // sv6_validator: '4pUXGkvvybNyTeWXEBlesr9qcYCQh2sh',
    // sv7_validator: '2cfFl6z5huY4rVYvxOEja8MvDdplYCDW',
    // sv8_validator: 'JYvSRekV1E5EUZ2sJ494YyHXbxR3OHIR',
    // sv9_validator: 'BABNqQ3m5ROTGJTlTHVlIckS3cwJ0M0w',
    // sv10_validator: 'EKBJkDcOHosrnhLALfrQYG6Uc4Csqwbe',
    // sv11_validator: '8jpCSqSkLxdY8zdmJwm0XXRfxFnPNAhG',
    // sv12_validator: 'PEMwunsstamR1c5k3LdjVInTKlVTkeb6',
    // sv13_validator: 'eqssDmClrmtQFTgJ7XIP7RDdhcD6iGfx',
    // sv14_validator: 'luGkjf4AvM5PYhmi3X5rFmKLzxHTBlgz',
    // sv15_validator: 'gL9Iv3iUiPTtDvyEZ9b4wCcTvz3G6qys',
    // sv16_validator: '6ANtCorumVE8Ur7n1gJ8Gfvgv5pa96mZ',
  };

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

  return pulumi
    .all([validator1UiApp.id, splitwellUiApp.id])
    .apply(([validator1UiId, splitwellUiId]) =>
      baseAuth0.apply(auth0Cfg => ({
        ...auth0Cfg,
        appToClientId: {
          ...extraAppToClientIds,
          ...auth0Cfg.appToClientId,
        } as ClientIdMap,
        namespaceToUiToClientId: {
          ...auth0Cfg.namespaceToUiToClientId,
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
        } as NamespaceToClientIdMapMap,
      }))
    );
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
): pulumi.Output<Auth0Config> {
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
        } as ClientIdMap,

        namespaceToUiToClientId: nsToUiToClientId,

        appToApiAudience: {
          participant: ledgerApiAudience,
          sv: svApiAudience,
          validator: validatorApiAudience,
        } as AudienceMap,

        appToClientAudience: {
          sv: ledgerApiAudience,
          validator: ledgerApiAudience,
        } as AudienceMap,

        fixedTokenCacheName: fixedTokenCacheName,

        auth0Domain: auth0Domain,
        auth0MgtClientId: auth0MgtClientId,
        auth0MgtClientSecret: '',
      };
    });
}

function validatorRunbookAuth0(
  clusterBasename: string,
  dnsNames: string[]
): pulumi.Output<Auth0Config> {
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
      } as ClientIdMap,

      namespaceToUiToClientId: {
        validator: {
          wallet: walletUiAppId,
          cns: ansUiAppId,
        },
      } as NamespaceToClientIdMapMap,

      appToApiAudience: {
        participant: 'https://ledger_api.example.com', // The Ledger API in the validator-test tenant
        validator: 'https://validator.example.com/api', // The Validator App API in the validator-test tenant
      } as AudienceMap,

      appToClientAudience: {
        validator: 'https://ledger_api.example.com',
      } as AudienceMap,

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
    const auth0Cfg = mainNetAuth0(clusterBasename, dnsNames);
    return auth0Cfg.apply(mainnetCfg => {
      const r: Auth0ClusterConfig = {
        mainnet: mainnetCfg,
      };
      return r;
    });
  } else {
    const spliceAuth0Cfg = nonMainNetAuth0(clusterBasename, dnsNames);
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
