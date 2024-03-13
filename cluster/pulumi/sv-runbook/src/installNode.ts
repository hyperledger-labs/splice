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
  installMigrationIdSpecificComponent,
  isDevNet,
  loadYamlFromFile,
  participantBootstrapDumpSecretName,
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
  GlobalDomainMigrationConfig,
  ValidatorTopupConfig,
  svValidatorTopupConfig,
  svOnboardingPollingInterval,
} from 'cn-pulumi-common';
import { retry } from 'cn-pulumi-common/src/retries';
import fetch from 'node-fetch';

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
const globalDomainMigrationConfig = GlobalDomainMigrationConfig.fromEnv();

const DEFAULT_AUDIENCE = 'https://canton.network.global';

export async function installNode(
  auth0Client: Auth0Client,
  svNamespaceStr: string,
  svAppConfig: SvAppConfig,
  validatorAppConfig: ValidatorAppConfig,
  resolveValidator1PartyId: () => Promise<string> = getValidator1PartyId
): Promise<void> {
  console.error(
    localCharts
      ? 'Using locally built charts'
      : `Using charts from the artifactory, version ${version}`
  );
  console.error(`TARGET_CLUSTER: ${TARGET_CLUSTER}`);
  console.error(`Installing SV node in namespace: ${svNamespaceStr}`);

  const xns = exactNamespace(svNamespaceStr, true);

  console.error(`Using migration config: ${globalDomainMigrationConfig}`);

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

  const { sv, validator } = await installSvAndValidator(
    {
      xns,
      globalDomainMigrationConfig,
      participantBootstrapDumpSecret,
      auth0Client,
      imagePullDeps,
      loopback,
      backupConfigSecret,
      backupConfig,
      topupConfig: svValidatorTopupConfig,
      svKey,
      onboardingName: svAppConfig.onboardingName,
      validatorWalletUserName: validatorAppConfig.walletUserName,
      disableOnboardingParticipantPromotionDelay:
        svAppConfig.disableOnboardingParticipantPromotionDelay,
    },
    resolveValidator1PartyId
  );

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
      ingress: {
        globalDomain: {
          activeMigrationId: globalDomainMigrationConfig.activeMigrationId.toString(),
          migrationId: globalDomainMigrationConfig.activeMigrationId.toString(),
        },
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
  globalDomainMigrationConfig: GlobalDomainMigrationConfig;
  onboarding?: ExpectedValidatorOnboarding;
  backupConfig?: BackupConfig;
  participantBootstrapDumpSecret?: pulumi.Resource;
  topupConfig?: ValidatorTopupConfig;
  imagePullDeps: CnInput<pulumi.Resource>[];
  loopback: k8s.helm.v3.Release | null;
  backupConfigSecret?: pulumi.Resource;
  svKey: CnInput<SvIdKey>;
  onboardingName: string;
  validatorWalletUserName: string;
  disableOnboardingParticipantPromotionDelay: boolean;
};

async function installSvAndValidator(
  config: SvConfig,
  resolveValidator1PartyId: () => Promise<string> = getValidator1PartyId
) {
  const {
    xns,
    globalDomainMigrationConfig,
    participantBootstrapDumpSecret,
    topupConfig,
    auth0Client,
    imagePullDeps,
    loopback,
    backupConfigSecret,
    backupConfig,
    svKey,
    onboardingName,
    validatorWalletUserName,
    disableOnboardingParticipantPromotionDelay,
  } = config;

  const globalDomain = installGlobalDomainNode(
    xns,
    onboardingName,
    globalDomainMigrationConfig,
    imagePullDeps
  );

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
  const svKeySecret_ = svKeySecret(xns, svKey);

  const participantPgValues = loadYamlFromFile(
    `${REPO_ROOT}/apps/app/src/pack/examples/sv-helm/postgres-values-participant.yaml`
  );
  const participantPg = installPostgres(
    xns,
    `participant-pg`,
    'participant-pg-secret',
    participantPgValues
  );

  const participant = installMigrationIdSpecificComponent(
    globalDomainMigrationConfig,
    (migrationId, isActive) => {
      const participantValues: ChartValues = {
        ...loadYamlFromFile(
          `${REPO_ROOT}/apps/app/src/pack/examples/sv-helm/participant-values.yaml`,
          {
            MIGRATION_ID: migrationId.toString(),
            OIDC_AUTHORITY_URL: auth0Client.getCfg().auth0Domain,
            YOUR_NODE_NAME: onboardingName,
          }
        ),
        metrics: {
          enable: true,
        },
        disableAutoInit:
          !!participantBootstrapDumpSecret ||
          globalDomainMigrationConfig.isRunningMigration() ||
          !isActive,
      };

      const participantValuesWithSpecifiedAud: ChartValues = {
        ...participantValues,
        auth: {
          ...participantValues.auth,
          targetAudience: auth0Client.getCfg().appToApiAudience['participant'] || DEFAULT_AUDIENCE,
        },
      };

      return installCNRunbookHelmChart(
        xns,
        `participant-${migrationId}`,
        'cn-participant',
        participantValuesWithSpecifiedAud,
        localCharts,
        version,
        imagePullDeps
          .concat([participantPg, svAppSecret, svKeySecret_])
          .concat(loopback !== null ? loopback : [])
      );
    }
  ).activeComponent;

  const appsPgValues = loadYamlFromFile(
    `${REPO_ROOT}/apps/app/src/pack/examples/sv-helm/postgres-values-apps.yaml`
  );
  const appsPg = installPostgres(xns, 'apps-pg', 'apps-pg-secret', appsPgValues);

  const sv234NameSet = new Set<string>(['Digital-Asset-2', 'Digital-Asset-3', 'Digital-Asset-4']);

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
      MIGRATION_ID: globalDomainMigrationConfig.activeMigrationId.toString(),
    }
  );

  const validator1PartyId: pulumi.Output<string> = pulumi.Output.create(resolveValidator1PartyId());
  const svValues: ChartValues = {
    ...valuesFromYamlFile,
    participantIdentitiesDumpImport: participantBootstrapDumpSecret
      ? { secretName: participantBootstrapDumpSecretName }
      : undefined,
    approvedSvIdentities,
    domain: {
      ...(valuesFromYamlFile.domain || {}),
      sequencerPruningConfig,
    },
    migration: {
      ...valuesFromYamlFile.migration,
      migrating: globalDomainMigrationConfig.isRunningMigration()
        ? true
        : valuesFromYamlFile.migration.migrating,
    },
    metrics: {
      enable: true,
    },
    extraBeneficiaries: [
      {
        partyId: validator1PartyId,
        percentage: '33.33',
      },
    ],
    onboardingPollingInterval: svOnboardingPollingInterval,
    disableOnboardingParticipantPromotionDelay,
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
      MIGRATION_ID: globalDomainMigrationConfig.activeMigrationId.toString(),
    }),
    metrics: {
      enable: true,
    },
  };

  const scanValuesWithFixedTokens = {
    ...scanValues,
    ...fixedTokensValue,
  };

  installCNRunbookHelmChart(
    xns,
    `scan`,
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
      TRUSTED_SCAN_URL: `http://scan-app.${xns.logicalName}:5012`,
    }),
    ...loadYamlFromFile(
      `${REPO_ROOT}/apps/app/src/pack/examples/sv-helm/sv-validator-values.yaml`,
      {
        TARGET_CLUSTER: TARGET_CLUSTER,
        MIGRATION_ID: globalDomainMigrationConfig.activeMigrationId.toString(),
      }
    ),
    metrics: {
      enable: true,
    },
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

async function getValidator1PartyId(): Promise<string> {
  return retry('getValidator1PartyId', 1000, 100, async () => {
    const response = await fetch(
      `https://wallet.validator1.${CLUSTER_BASENAME}.network.canton.global/api/validator/v0/validator-user`
    );
    const json = await response.json();
    if (!response.ok) {
      throw new Error(`Response is not OK: ${JSON.stringify(json)}`);
    } else if (!json.party_id) {
      throw new Error(`JSON does not contain party_id: ${JSON.stringify(json)}`);
    } else {
      return json.party_id;
    }
  });
}
