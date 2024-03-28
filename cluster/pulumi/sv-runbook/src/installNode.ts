import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import {
  Auth0Client,
  BackupConfig,
  ChartValues,
  ansUiSecret,
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
  DecentralizedSynchronizerMigrationConfig,
  ValidatorTopupConfig,
  svValidatorTopupConfig,
  svOnboardingPollingInterval,
  defaultVersion,
  disableCantonAutoInit,
} from 'cn-pulumi-common';
import { retry } from 'cn-pulumi-common/src/retries';
import { failOnAppVersionMismatch } from 'cn-pulumi-common/src/upgrades';
import fetch from 'node-fetch';

import { SvAppConfig, ValidatorAppConfig } from './config';
import { installDecentralizedSynchronizerNode } from './decentralizedSynchronizer';
import { installPostgres } from './postgres';
import { CLUSTER_BASENAME, TARGET_CLUSTER } from './utils';

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
const decentralizedSynchronizerMigrationConfig = DecentralizedSynchronizerMigrationConfig.fromEnv();

const DEFAULT_AUDIENCE = 'https://canton.network.global';

export async function installNode(
  auth0Client: Auth0Client,
  svNamespaceStr: string,
  svAppConfig: SvAppConfig,
  validatorAppConfig: ValidatorAppConfig,
  resolveValidator1PartyId: () => Promise<string> = getValidator1PartyId
): Promise<void> {
  console.error(
    defaultVersion.type === 'local'
      ? 'Using locally built charts by default'
      : `Using charts from the artifactory by default, version ${defaultVersion.version}`
  );
  console.error(`TARGET_CLUSTER: ${TARGET_CLUSTER}`);
  console.error(`Installing SV node in namespace: ${svNamespaceStr}`);

  const xns = exactNamespace(svNamespaceStr, true);

  console.error(
    `Using migration config: ${JSON.stringify(decentralizedSynchronizerMigrationConfig)}`
  );

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
      ? installLoopback(xns, CLUSTER_BASENAME, defaultVersion)
      : null;

  // For the runbooks, we pull images from artifactory when using remote charts, and need creds for that
  const imagePullDeps = defaultVersion.type === 'local' ? [] : imagePullSecret(xns);

  const svKey = svKeyFromSecret('sv');

  const { sv, validator } = await installSvAndValidator(
    {
      xns,
      decentralizedSynchronizerMigrationConfig,
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

  // For the runbooks, we pull images from artifactory when using remote charts, and need creds for that
  const ingressImagePullDeps =
    defaultVersion.type === 'local' ? [] : imagePullSecretByNamespaceName('cluster-ingress');
  installCNRunbookHelmChartByNamespaceName(
    xns.logicalName,
    xns.logicalName,
    'cluster-ingress-sv',
    'cn-cluster-ingress-runbook',
    {
      cluster: {
        hostname: `${CLUSTER_BASENAME}.network.canton.global`,
        svNamespace: svNamespaceStr,
      },
      ingress: {
        decentralizedSynchronizer: {
          migrationIds: decentralizedSynchronizerMigrationConfig
            .allMigrationInfos()
            .map(x => x.migrationId.toString()),
        },
      },
    },
    defaultVersion,
    ingressImagePullDeps.concat([sv, validator])
  );
}

type SvConfig = {
  auth0Client: Auth0Client;
  xns: ExactNamespace;
  decentralizedSynchronizerMigrationConfig: DecentralizedSynchronizerMigrationConfig;
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
    decentralizedSynchronizerMigrationConfig,
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

  const decentralizedSynchronizer = installDecentralizedSynchronizerNode(
    xns,
    onboardingName,
    decentralizedSynchronizerMigrationConfig,
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
    decentralizedSynchronizerMigrationConfig,
    (migrationId, isActive, version) => {
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
          disableCantonAutoInit ||
          !!participantBootstrapDumpSecret ||
          decentralizedSynchronizerMigrationConfig.isRunningMigration() ||
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

  const sv234NameSet = new Set<string>([
    'Digital-Asset-Eng-2',
    'Digital-Asset-Eng-3',
    'Digital-Asset-Eng-4',
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
      MIGRATION_ID: decentralizedSynchronizerMigrationConfig.active.migrationId.toString(),
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
      migrating: decentralizedSynchronizerMigrationConfig.isRunningMigration()
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
    failOnAppVersionMismatch: failOnAppVersionMismatch(),
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
    defaultVersion,
    imagePullDeps
      .concat([participant, decentralizedSynchronizer])
      .concat([svAppSecret, svAppUISecret, appsPg])
      .concat(participantBootstrapDumpSecret ? [participantBootstrapDumpSecret] : [])
  );

  const scanValues: ChartValues = {
    ...loadYamlFromFile(`${REPO_ROOT}/apps/app/src/pack/examples/sv-helm/scan-values.yaml`, {
      TARGET_CLUSTER: TARGET_CLUSTER,
      MIGRATION_ID: decentralizedSynchronizerMigrationConfig.active.migrationId.toString(),
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
    defaultVersion,
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
        MIGRATION_ID: decentralizedSynchronizerMigrationConfig.active.migrationId.toString(),
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

  const ansUiClientId = svNameSpaceAuth0Clients['ans'];
  if (!ansUiClientId) {
    throw new Error('No CNS ui client id in auth0 config');
  }

  const validator = installCNRunbookHelmChart(
    xns,
    'validator',
    'cn-validator',
    validatorValuesWithMaybeTopups,
    defaultVersion,
    imagePullDeps
      .concat([sv, participant])
      .concat([svValidatorAppSecret, svValidatorUISecret])
      .concat([ansUiSecret(xns, auth0Client, ansUiClientId)])
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
