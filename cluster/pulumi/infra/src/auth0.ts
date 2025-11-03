// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as auth0 from '@pulumi/auth0';
import * as pulumi from '@pulumi/pulumi';
import {
  ansDomainPrefix,
  Auth0Config,
  Auth0ClusterConfig,
  config,
  isMainNet,
  clusterProdLike,
  Auth0NamespaceConfig,
  DEFAULT_AUDIENCE,
  NamespacedAuth0Configs,
  fixedTokens,
} from '@lfdecentralizedtrust/splice-pulumi-common';
import {
  standardSvConfigs,
  extraSvConfigs,
  dsoSize,
} from '@lfdecentralizedtrust/splice-pulumi-common-sv';

function tokenLifetime(): number {
  return fixedTokens() ? 2592000 : 86400; // TODO(DACH-NY/canton-network-internal#2114): Move this to the cluster config? We want it to be long for fixed token clusters
}

function ledgerApiAudience(
  svNamespace: string,
  clusterBasename: string,
  auth0DomainProvider: auth0.Provider
): pulumi.Output<string> {
  if (isMainNet) {
    // TODO(DACH-NY/canton-network-internal#2873): get rid of this
    return pulumi.output('https://ledger_api.main.digitalasset.com');
  }
  if (clusterProdLike) {
    // On prod clusters, we create a ledger API per SV namespace
    const auth0Api = new auth0.ResourceServer(
      `LedgerApi${svNamespace.replace(/-/g, '')}`,
      {
        name: `Ledger API for SV ${svNamespace} on ${clusterBasename} (Pulumi managed)`,
        identifier: `https://ledger_api.${svNamespace}.${clusterBasename}.canton.network`,
        allowOfflineAccess: true, // TODO(DACH-NY/canton-network-internal#2114): is this still needed?
        tokenLifetime: tokenLifetime(),
      },
      { provider: auth0DomainProvider }
    );

    new auth0.ResourceServerScopes(
      `LedgerApiScopes${svNamespace.replace(/-/g, '')}`,
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
  svNamespace: string,
  clusterBasename: string,
  auth0DomainProvider: auth0.Provider
): pulumi.Output<string> {
  if (isMainNet) {
    // TODO(DACH-NY/canton-network-internal#2873): get rid of this
    return pulumi.output('https://sv.main.digitalasset.com');
  }
  if (clusterProdLike) {
    // On prod clusters, we create a SV App API per SV namespace
    const auth0Api = new auth0.ResourceServer(
      `SvAppApi${svNamespace.replace(/-/g, '')}`,
      {
        name: `SV App API for SV ${svNamespace} on ${clusterBasename} (Pulumi managed)`,
        identifier: `https://sv.${svNamespace}.${clusterBasename}.canton.network/api`,
        allowOfflineAccess: true, // TODO(DACH-NY/canton-network-internal#2114): is this still needed?
        tokenLifetime: tokenLifetime(),
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
  svNamespace: string,
  clusterBasename: string,
  auth0DomainProvider: auth0.Provider
): pulumi.Output<string> {
  if (isMainNet) {
    // TODO(DACH-NY/canton-network-internal#2873): get rid of this
    return pulumi.output('https://validator.main.digitalasset.com');
  }
  if (clusterProdLike) {
    // On prod clusters, we create a Validator App API per SV namespace
    const auth0Api = new auth0.ResourceServer(
      `ValidatorAppApi${svNamespace.replace(/-/g, '')}`,
      {
        name: `Validator App API for SV ${svNamespace} on ${clusterBasename} (Pulumi managed)`,
        identifier: `https://validator.${svNamespace}.${clusterBasename}.canton.network/api`,
        allowOfflineAccess: true, // TODO(DACH-NY/canton-network-internal#2114): is this still needed?
        tokenLifetime: tokenLifetime(),
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

    // TODO(DACH-NY/canton-network-internal#2873): Of course on MainNet we use a different default audience...
    const legacyLedgerApiAud = isMainNet
      ? 'https://ledger_api.main.digitalasset.com'
      : 'https://canton.network.global';
    if (ledgerApiAudValue !== legacyLedgerApiAud) {
      // TODO(DACH-NY/canton-network-internal#2873): For now, we also grant all apps access to the old default ledger API
      // audience, to un-break it until we clean up the audiences we use.
      new auth0.ClientGrant(
        `${resourceName}LegacyGrant`,
        {
          clientId: ret.id,
          audience: legacyLedgerApiAud,
          scopes: ['daml_ledger_api'],
        },
        {
          provider: auth0DomainProvider,
        }
      );
    }

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

function auth0ForSvNamespace(
  clusterBasename: string,
  namespace: string,
  provider: auth0.Provider,
  ingressName: string,
  dnsNames: string[]
): pulumi.Output<Auth0NamespaceConfig> {
  const ledgerApiAud = ledgerApiAudience(namespace, clusterBasename, provider);
  const svAppAud = svAppAudience(namespace, clusterBasename, provider);
  const validatorAud = validatorAppAudience(namespace, clusterBasename, provider);
  const validatorApp = newM2MApp(
    `${namespace.replace(/-/g, '')}ValidatorBackendApp`,
    `${namespace.replace(/-/g, '').toUpperCase()} Validator Backend`,
    `Used for the Validator backend for SV ${namespace} on ${clusterBasename}`,
    clusterBasename,
    ledgerApiAud,
    validatorAud,
    provider
  );
  const svApp = newM2MApp(
    `${namespace.replace(/-/g, '')}SvBackendApp`,
    `${namespace.replace(/-/g, '').toUpperCase()} SV Backend`,
    `Used for the SV backend for SV ${namespace} on ${clusterBasename}`,
    clusterBasename,
    ledgerApiAud,
    svAppAud,
    provider
  );
  const uiApp = newUiApp(
    `${namespace.replace(/-/g, '')}UiApp`,
    `${namespace.replace(/-/g, '').toUpperCase()} UI`,
    `Used for the Wallet, ANS and SV UIs for SV ${namespace}`,
    ['wallet', ansDomainPrefix, 'sv'],
    ingressName,
    clusterBasename,
    dnsNames,
    provider
  );

  return pulumi
    .all([ledgerApiAud, svAppAud, validatorAud, validatorApp.id, svApp.id, uiApp.id])
    .apply(
      ([ledgerApiAudValue, svAppAudValue, validatorAudValue, validatorAppId, svAppId, uiAppId]) => {
        return {
          audiences: {
            ledgerApi: ledgerApiAudValue,
            svAppApi: svAppAudValue,
            validatorApi: validatorAudValue,
          },
          backendClientIds: {
            validator: validatorAppId,
            svApp: svAppId,
          },
          uiClientIds: {
            sv: uiAppId,
            wallet: uiAppId,
            cns: uiAppId,
          },
        };
      }
    );
}

function svsOnlyAuth0(
  clusterBasename: string,
  dnsNames: string[],
  provider: auth0.Provider,
  svs: svAuth0Params[],
  auth0Domain: string,
  auth0MgtClientId: string,
  fixedTokenCacheName: string
): pulumi.Output<Auth0Config> {
  const svAuth0CfgPromises = svs.map(sv => {
    const auth0NamespaceConfig = auth0ForSvNamespace(
      clusterBasename,
      sv.namespace,
      provider,
      sv.ingressName,
      dnsNames
    );
    return auth0NamespaceConfig.apply(cfg => ({
      namespace: sv.namespace,
      cfg: cfg,
    }));
  });

  const namespacedConfig = pulumi.all(svAuth0CfgPromises).apply(svCfgs => {
    return svCfgs.reduce(
      (acc: Record<string, Auth0NamespaceConfig>, svCfg) => {
        acc[svCfg.namespace] = svCfg.cfg;
        return acc;
      },
      {} as Record<string, Auth0NamespaceConfig>
    );
  });

  return namespacedConfig.apply(namespacedConfig => {
    return {
      namespacedConfigs: namespacedConfig,
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
  const sv1: svAuth0Params = {
    namespace: 'sv-1',
    description: 'sv-1 (Digital-Asset 2)',
    ingressName: 'sv-2', // Ingress name of sv-1 is sv-2!
  };

  const extraSvs: svAuth0Params[] = extraSvConfigs.map(sv => ({
    namespace: sv.nodeName,
    description: sv.onboardingName,
    ingressName: sv.ingressName,
  }));

  return svsOnlyAuth0(
    clusterBasename,
    dnsNames,
    provider,
    [sv1, ...extraSvs],
    auth0Domain,
    auth0MgtClientId,
    'DO_NOT_USE'
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

  const standardSvs: svAuth0Params[] = standardSvConfigs
    .map(sv => ({
      namespace: sv.nodeName,
      description: sv.nodeName.replace(/-/g, '').toUpperCase(),
      ingressName: sv.ingressName,
    }))
    .slice(0, dsoSize);
  const extraSvs: svAuth0Params[] = extraSvConfigs.map(sv => ({
    namespace: sv.nodeName,
    description: sv.onboardingName,
    ingressName: sv.ingressName,
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
  const validator1Auth0Config: pulumi.Output<Auth0NamespaceConfig> = validator1UiApp.id.apply(
    clientId => {
      return {
        audiences: {
          ledgerApi: DEFAULT_AUDIENCE,
          validatorApi: DEFAULT_AUDIENCE,
        },
        backendClientIds: {
          // hardcoded client IDs
          // TODO(tech-debt) consider folding into main config or into `config.yaml`
          // TODO(DACH-NY/canton-network-internal#2873): consider creating these apps in pulumi instead
          validator: 'cf0cZaTagQUN59C1HBL2udiIBdFh2CWq',
        },
        uiClientIds: {
          wallet: clientId,
          cns: clientId,
          splitwell: clientId,
        },
      };
    }
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
  const splitwellAuth0Config: pulumi.Output<Auth0NamespaceConfig> = splitwellUiApp.id.apply(
    clientId => {
      return {
        audiences: {
          ledgerApi: DEFAULT_AUDIENCE,
          validatorApi: DEFAULT_AUDIENCE,
        },
        backendClientIds: {
          // hardcoded client IDs
          // TODO(tech-debt) consider folding into main config or into `config.yaml`
          // TODO(DACH-NY/canton-network-internal#2873): consider creating these apps in pulumi instead
          validator: 'hqpZ6TP0wGyG2yYwhH6NLpuo0MpJMQZW',
          splitwell: 'ekPlYxilradhEnpWdS80WfW63z1nHvKy',
        },
        uiClientIds: {
          wallet: clientId,
          cns: clientId,
          splitwell: clientId,
        },
      };
    }
  );

  return pulumi
    .all([baseAuth0, validator1Auth0Config, splitwellAuth0Config])
    .apply(([baseCfg, validator1Cfg, splitwellCfg]) => {
      baseCfg.namespacedConfigs['validator1'] = validator1Cfg;
      baseCfg.namespacedConfigs['splitwell'] = splitwellCfg;

      return baseCfg;
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
      const namespacedConfig: NamespacedAuth0Configs = {};
      namespacedConfig[namespace] = {
        audiences: {
          ledgerApi: ledgerApiAudience,
          svAppApi: svApiAudience,
          validatorApi: validatorApiAudience,
        },
        backendClientIds: {
          validator: validatorBackendClientId,
          svApp: svBackendClientId,
        },
        uiClientIds: {
          sv: svUiAppId,
          wallet: walletUiAppId,
          cns: ansUiAppId,
        },
      };

      return {
        namespacedConfigs: namespacedConfig,
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
    const namespacedConfig: NamespacedAuth0Configs = {
      validator: {
        audiences: {
          ledgerApi: 'https://ledger_api.example.com', // The Ledger API in the validator-test tenant
          validatorApi: 'https://validator.example.com/api', // The Validator App API in the validator-test tenant
        },
        backendClientIds: {
          validator: 'cznBUeB70fnpfjaq9TzblwiwjkVyvh5z',
        },
        uiClientIds: {
          wallet: walletUiAppId,
          cns: ansUiAppId,
        },
      },
    };

    return {
      namespacedConfigs: namespacedConfig,
      fixedTokenCacheName: 'auth0-fixed-token-cache-validator-test',
      auth0Domain: auth0Domain,
      auth0MgtClientId: auth0MgtClientId,
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

    return spliceAuth0Cfg.apply(splice => {
      return svRunbookAuth0Cfg.apply(sv => {
        return validatorRunbookAuth0Cfg.apply(validator => {
          return {
            cantonNetwork: splice,
            svRunbook: sv,
            validatorRunbook: validator,
          };
        });
      });
    });
  }
}
