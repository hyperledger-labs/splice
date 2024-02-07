import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import {
  Auth0Client,
  BackupConfig,
  ChartValues,
  cnsUiSecret,
  envFlag,
  exactNamespace,
  ExactNamespace,
  fixedTokens,
  setupBootstrapping,
  imagePullSecretByNamespaceName,
  installCNRunbookHelmChart,
  installCNRunbookHelmChartByNamespaceName,
  isDevNet,
  loadYamlFromFile,
  participantBootstrapDumpSecretName,
  domainFeesConfig,
  ValidatorTopupConfig,
  REPO_ROOT,
  svAppSecrets,
  svKeySecret,
  svKeyFromSecret,
  validatorSecrets,
  ExpectedValidatorOnboarding,
  SvIdKey,
  installLoopback,
  imagePullSecret,
  CnInput,
  sequencerPruningConfig,
} from 'cn-pulumi-common';

import { SvAppConfig, ValidatorAppConfig } from './config';
import { installGlobalDomainNode } from './globalDomain';
import { installPostgres } from './postgres';
import { CLUSTER_BASENAME, localCharts, TARGET_CLUSTER, version } from './utils';

if (!isDevNet) {
  console.error('Launching in non-devnet mode');
}

const singleSv = envFlag('SINGLE_SV') || !isDevNet;
if (singleSv) {
  console.error('Launching with a single SV');
}

type BootstrapCliConfig = {
  cluster: string;
  date: string;
};

type ApprovedSvIdentity = {
  name: string;
  publicKey: string;
};

const bootstrappingConfig: BootstrapCliConfig = process.env.BOOTSTRAPPING_CONFIG
  ? JSON.parse(process.env.BOOTSTRAPPING_CONFIG)
  : undefined;

const participantIdentitiesFile = process.env.PARTICIPANT_IDENTITIES_FILE;

const DEFAULT_AUDIENCE = 'https://canton.network.global';

export async function installNode(
  auth0Client: Auth0Client,
  svNamespaceStr: string,
  svAppConfig: SvAppConfig,
  validatorAppConfig: ValidatorAppConfig
): Promise<void> {
  console.error(
    localCharts
      ? 'Using locally built charts'
      : `Using charts from the artifactory, version ${version}`
  );
  console.error(`TARGET_CLUSTER: ${TARGET_CLUSTER}`);
  console.error(`Installing SV node in namespace: ${svNamespaceStr}`);

  const xns = exactNamespace(svNamespaceStr, true);

  const { participantBootstrapDumpSecret, backupConfigSecret, backupConfig } =
    await setupBootstrapping({
      xns,
      RUNBOOK_NAMESPACE: svNamespaceStr,
      CLUSTER_BASENAME,
      participantIdentitiesFile,
      bootstrappingConfig,
    });

  const loopback =
    TARGET_CLUSTER === CLUSTER_BASENAME
      ? installLoopback(xns, CLUSTER_BASENAME, localCharts, version)
      : null;

  const imagePullDeps = localCharts ? [] : imagePullSecret(xns);

  const svKey = svKeyFromSecret('sv');

  const topupConfig: ValidatorTopupConfig = {
    targetThroughput: domainFeesConfig.targetThroughput,
    minTopupInterval: domainFeesConfig.minTopupInterval,
  };

  const { sv, validator } = await installSvAndValidator({
    xns,
    participantBootstrapDumpSecret,
    auth0Client,
    imagePullDeps,
    loopback,
    backupConfigSecret,
    backupConfig,
    topupConfig,
    svKey,
    onboardingName: svAppConfig.onboardingName,
    cometBftConnectionUri: svAppConfig.cometBftConnectionUri,
    validatorWalletUserName: validatorAppConfig.walletUserName,
  });

  const ingressImagePullDeps = localCharts ? [] : imagePullSecretByNamespaceName('cluster-ingress');
  installCNRunbookHelmChartByNamespaceName(
    xns.logicalName,
    'cluster-ingress-sv',
    'cn-cluster-ingress-runbook',
    {
      cluster: {
        hostname: `${CLUSTER_BASENAME}.network.canton.global`,
        svNamespace: svNamespaceStr,
      },
    },
    localCharts,
    version,
    ingressImagePullDeps.concat([sv, validator])
  );
}

type SvConfig = {
  auth0Client: Auth0Client;
  xns: ExactNamespace;
  onboarding?: ExpectedValidatorOnboarding;
  backupConfig?: BackupConfig;
  participantBootstrapDumpSecret?: pulumi.Resource;
  topupConfig?: ValidatorTopupConfig;
  imagePullDeps: CnInput<pulumi.Resource>[];
  loopback: k8s.helm.v3.Release | null;
  backupConfigSecret?: pulumi.Resource;
  svKey: CnInput<SvIdKey>;
  onboardingName: string;
  cometBftConnectionUri: string;
  validatorWalletUserName: string;
};

async function installSvAndValidator(config: SvConfig) {
  const {
    xns,
    participantBootstrapDumpSecret,
    topupConfig,
    auth0Client,
    imagePullDeps,
    loopback,
    backupConfigSecret,
    backupConfig,
    svKey,
    onboardingName,
    cometBftConnectionUri,
    validatorWalletUserName,
  } = config;

  const globalDomain = installGlobalDomainNode(xns, onboardingName, imagePullDeps);

  const participantValues: ChartValues = {
    ...loadYamlFromFile(`${REPO_ROOT}/apps/app/src/pack/examples/sv-helm/participant-values.yaml`, {
      TARGET_CLUSTER: TARGET_CLUSTER,
      OIDC_AUTHORITY_URL: auth0Client.getCfg().auth0Domain,
    }),
    disableAutoInit: !!participantBootstrapDumpSecret,
  };

  const participantValuesWithSpecifiedAud: ChartValues = {
    ...participantValues,
    auth: {
      ...participantValues.auth,
      targetAudience: auth0Client.getCfg().appToApiAudience['participant'] || DEFAULT_AUDIENCE,
    },
  };

  const svNameSpaceAuth0Clients = auth0Client.getCfg().namespaceToUiToClientId['sv'];
  if (!svNameSpaceAuth0Clients) {
    throw new Error('No SV namespace in auth0 config');
  }
  const svUiClientId = svNameSpaceAuth0Clients['sv'];
  if (!svUiClientId) {
    throw new Error('No SV ui client id in auth0 config');
  }

  const { appSecret: svAppSecret, uiSecret: svAppUISecret } = await svAppSecrets(
    xns,
    auth0Client,
    svUiClientId
  );

  const participantPgValues = loadYamlFromFile(
    `${REPO_ROOT}/apps/app/src/pack/examples/sv-helm/postgres-values-participant.yaml`
  );
  const participantPg = installPostgres(xns, 'participant-pg', participantPgValues);

  const participant = installCNRunbookHelmChart(
    xns,
    'participant',
    'cn-participant',
    participantValuesWithSpecifiedAud,
    localCharts,
    version,
    imagePullDeps
      .concat([participantPg, svAppSecret, svKeySecret(xns, svKey)])
      .concat(loopback !== null ? loopback : [])
  );

  const appsPgValues = loadYamlFromFile(
    `${REPO_ROOT}/apps/app/src/pack/examples/sv-helm/postgres-values-apps.yaml`
  );
  const appsPg = installPostgres(xns, 'apps-pg', appsPgValues);

  const sv234NameSet = new Set<string>([
    'Canton-Foundation-2',
    'Canton-Foundation-3',
    'Canton-Foundation-4',
  ]);

  const allApprovedSvIdentities = (
    isDevNet
      ? loadYamlFromFile(
          `${REPO_ROOT}/apps/app/src/pack/examples/sv-helm/approved-sv-id-values-dev.yaml`
        )
      : loadYamlFromFile(
          `${REPO_ROOT}/apps/app/src/pack/examples/sv-helm/approved-sv-id-values-test.yaml`
        )
  ).approvedSvIdentities;

  const approvedSvIdentities = singleSv
    ? allApprovedSvIdentities.filter((id: ApprovedSvIdentity) => !sv234NameSet.has(id.name))
    : allApprovedSvIdentities;

  const valuesFromYamlFile = loadYamlFromFile(
    `${REPO_ROOT}/apps/app/src/pack/examples/sv-helm/sv-values.yaml`,
    {
      TARGET_CLUSTER: TARGET_CLUSTER,
      YOUR_SV_NAME: onboardingName,
      OIDC_AUTHORITY_URL: auth0Client.getCfg().auth0Domain,
      YOUR_HOSTNAME: `${CLUSTER_BASENAME}.network.canton.global`,
    }
  );

  const svValues: ChartValues = {
    ...valuesFromYamlFile,
    participantIdentitiesDumpImport: participantBootstrapDumpSecret
      ? { secretName: participantBootstrapDumpSecretName }
      : undefined,
    approvedSvIdentities,
    cometBFT: {
      enabled: true,
      connectionUri: cometBftConnectionUri,
    },
    domain: {
      ...(valuesFromYamlFile.domain || {}),
      sequencerPruningConfig,
    },
  };

  const svValuesWithSpecifiedAud: ChartValues = {
    ...svValues,
    auth: {
      ...svValues.auth,
      audience: auth0Client.getCfg().appToApiAudience['sv'] || DEFAULT_AUDIENCE,
    },
  };

  const fixedTokensValue: ChartValues = {
    cluster: {
      fixedTokens: true,
    },
  };

  const svValuesWithFixedTokens = {
    ...svValuesWithSpecifiedAud,
    ...fixedTokensValue,
  };

  const walletUiClientId = svNameSpaceAuth0Clients['wallet'];
  if (!walletUiClientId) {
    throw new Error('No SV ui client id in auth0 config');
  }
  const { appSecret: svValidatorAppSecret, uiSecret: svValidatorUISecret } = await validatorSecrets(
    xns,
    auth0Client,
    walletUiClientId
  );

  const sv = installCNRunbookHelmChart(
    xns,
    'sv-app',
    'cn-sv-node',
    fixedTokens() ? svValuesWithFixedTokens : svValuesWithSpecifiedAud,
    localCharts,
    version,
    imagePullDeps
      .concat([participant, globalDomain])
      .concat([svAppSecret, svAppUISecret, appsPg])
      .concat(participantBootstrapDumpSecret ? [participantBootstrapDumpSecret] : [])
  );

  const scanValues: ChartValues = {
    ...loadYamlFromFile(`${REPO_ROOT}/apps/app/src/pack/examples/sv-helm/scan-values.yaml`, {
      TARGET_CLUSTER: TARGET_CLUSTER,
    }),
  };

  const scanValuesWithFixedTokens = {
    ...scanValues,
    ...fixedTokensValue,
  };

  installCNRunbookHelmChart(
    xns,
    'scan',
    'cn-scan',
    fixedTokens() ? scanValuesWithFixedTokens : scanValues,
    localCharts,
    version,
    imagePullDeps.concat([sv, participant, svAppSecret, appsPg])
  );

  const validatorValues = {
    ...loadYamlFromFile(`${REPO_ROOT}/apps/app/src/pack/examples/sv-helm/validator-values.yaml`, {
      TARGET_CLUSTER: TARGET_CLUSTER,
      OPERATOR_WALLET_USER_ID: validatorWalletUserName,
      OIDC_AUTHORITY_URL: auth0Client.getCfg().auth0Domain,
    }),
    ...loadYamlFromFile(`${REPO_ROOT}/apps/app/src/pack/examples/sv-helm/sv-validator-values.yaml`),
    participantIdentitiesDumpPeriodicBackup: backupConfig,
  };

  const validatorValuesWithSpecifiedAud: ChartValues = {
    ...validatorValues,
    auth: {
      ...validatorValues.auth,
      audience: auth0Client.getCfg().appToApiAudience['validator'] || DEFAULT_AUDIENCE,
      ledgerApiAudience: auth0Client.getCfg().appToApiAudience['participant'] || DEFAULT_AUDIENCE,
    },
  };

  const validatorValuesWithMaybeFixedTokens: ChartValues = {
    ...validatorValuesWithSpecifiedAud,
    ...(fixedTokens() ? fixedTokensValue : {}),
  };

  const validatorValuesWithMaybeTopups: ChartValues = {
    ...validatorValuesWithMaybeFixedTokens,
    topup: topupConfig ? { enabled: true, ...topupConfig } : { enabled: false },
  };

  const cnsUiClientId = svNameSpaceAuth0Clients['cns'];
  if (!cnsUiClientId) {
    throw new Error('No CNS ui client id in auth0 config');
  }

  const validator = installCNRunbookHelmChart(
    xns,
    'validator',
    'cn-validator',
    validatorValuesWithMaybeTopups,
    localCharts,
    version,
    imagePullDeps
      .concat([sv, participant])
      .concat([svValidatorAppSecret, svValidatorUISecret])
      .concat([cnsUiSecret(xns, auth0Client, cnsUiClientId)])
      .concat(backupConfigSecret ? [backupConfigSecret] : [])
      .concat([appsPg])
  );

  return { sv, validator };
}
