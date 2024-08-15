import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import * as semver from 'semver';
import {
  Auth0Client,
  BackupConfig,
  ChartValues,
  cnsUiSecret,
  config,
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
  CLUSTER_BASENAME,
  CLUSTER_HOSTNAME,
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
  SV_APP_HELM_CHART_TIMEOUT_SEC,
  approvedSvIdentities,
  daContactPoint,
  spliceInstanceNames,
  autoInitValues,
} from 'cn-pulumi-common';
import { CloudPostgres, CNPostgres } from 'cn-pulumi-common/src/postgres';
import { failOnAppVersionMismatch } from 'cn-pulumi-common/src/upgrades';

import { SvAppConfig, ValidatorAppConfig } from './config';
import { installDecentralizedSynchronizerNode } from './decentralizedSynchronizer';
import { installPostgres } from './postgres';

if (!isDevNet) {
  console.error('Launching in non-devnet mode');
}

const singleSv = config.envFlag('SINGLE_SV') || !isDevNet;
if (singleSv) {
  console.error('Launching with a single SV');
}

type BootstrapCliConfig = {
  cluster: string;
  date: string;
};

const bootstrappingConfig: BootstrapCliConfig = config.optionalEnv('BOOTSTRAPPING_CONFIG')
  ? JSON.parse(config.requireEnv('BOOTSTRAPPING_CONFIG'))
  : undefined;

const participantIdentitiesFile = config.optionalEnv('PARTICIPANT_IDENTITIES_FILE');
const decentralizedSynchronizerMigrationConfig = DecentralizedSynchronizerMigrationConfig.fromEnv();

const DEFAULT_AUDIENCE = 'https://canton.network.global';

export async function installNode(
  auth0Client: Auth0Client,
  svNamespaceStr: string,
  svAppConfig: SvAppConfig,
  validatorAppConfig: ValidatorAppConfig,
  resolveValidator1PartyId?: () => Promise<string>
): Promise<void> {
  console.error(
    defaultVersion.type === 'local'
      ? 'Using locally built charts by default'
      : `Using charts from the artifactory by default, version ${defaultVersion.version}`
  );
  console.error(`CLUSTER_BASENAME: ${CLUSTER_BASENAME}`);
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

  const loopback = installLoopback(xns, CLUSTER_HOSTNAME, defaultVersion);

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
        hostname: CLUSTER_HOSTNAME,
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

function persistenceForPostgres(pg: CNPostgres | CloudPostgres, values: ChartValues) {
  return {
    persistence: {
      ...values?.persistence,
      host: pg.address,
      secretName: pg.secretName,
      postgresName: pg.instanceName,
    },
    enablePostgresMetrics: true,
  };
}

async function installSvAndValidator(
  config: SvConfig,
  resolveValidator1PartyId?: () => Promise<string>
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

  const participantPg = installPostgres(
    xns,
    `participant-pg`,
    'participant-pg-secret',
    'postgres-values-participant.yaml'
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
          }
        ),
        metrics: {
          enable: true,
          migration: {
            id: migrationId,
            active: isActive,
          },
        },
      };

      const participantValuesWithSpecifiedAud: ChartValues = {
        ...participantValues,
        ...persistenceForPostgres(participantPg, participantValues),
        auth: {
          ...participantValues.auth,
          targetAudience: auth0Client.getCfg().appToApiAudience['participant'] || DEFAULT_AUDIENCE,
        },
        ...autoInitValues('cn-participant', version, onboardingName),
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

  const appsPg = installPostgres(xns, 'apps-pg', 'apps-pg-secret', 'postgres-values-apps.yaml');

  const valuesFromYamlFile = loadYamlFromFile(
    `${REPO_ROOT}/apps/app/src/pack/examples/sv-helm/sv-values.yaml`,
    {
      TARGET_HOSTNAME: CLUSTER_HOSTNAME,
      YOUR_SV_NAME: onboardingName,
      OIDC_AUTHORITY_URL: auth0Client.getCfg().auth0Domain,
      YOUR_HOSTNAME: CLUSTER_HOSTNAME,
      MIGRATION_ID: decentralizedSynchronizerMigrationConfig.active.migrationId.toString(),
      YOUR_CONTACT_POINT: daContactPoint,
    }
  );

  const supportsBeneficiariesWeight =
    defaultVersion.type == 'local' ||
    (defaultVersion.type == 'remote' && defaultVersion.version.startsWith('0.1.13')) ||
    semver.gt(defaultVersion.version, '0.1.13');

  const extraBeneficiaries = resolveValidator1PartyId
    ? [
        supportsBeneficiariesWeight
          ? {
              beneficiary: pulumi.Output.create(resolveValidator1PartyId()),
              weight: '3333',
            }
          : {
              partyId: pulumi.Output.create(resolveValidator1PartyId()),
              percentage: '33.33',
            },
      ]
    : [];
  const svValues: ChartValues = {
    ...valuesFromYamlFile,
    participantIdentitiesDumpImport: participantBootstrapDumpSecret
      ? { secretName: participantBootstrapDumpSecretName }
      : undefined,
    approvedSvIdentities: approvedSvIdentities(),
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
    ...spliceInstanceNames,
    extraBeneficiaries,
    onboardingPollingInterval: svOnboardingPollingInterval,
    disableOnboardingParticipantPromotionDelay,
    failOnAppVersionMismatch: failOnAppVersionMismatch(),
  };

  const svValuesWithSpecifiedAud: ChartValues = {
    ...svValues,
    ...persistenceForPostgres(appsPg, svValues),
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
      .concat(participantBootstrapDumpSecret ? [participantBootstrapDumpSecret] : []),
    SV_APP_HELM_CHART_TIMEOUT_SEC
  );

  const defaultScanValues = loadYamlFromFile(
    `${REPO_ROOT}/apps/app/src/pack/examples/sv-helm/scan-values.yaml`,
    {
      TARGET_HOSTNAME: CLUSTER_HOSTNAME,
      MIGRATION_ID: decentralizedSynchronizerMigrationConfig.active.migrationId.toString(),
    }
  );
  const scanValues: ChartValues = {
    ...defaultScanValues,
    ...persistenceForPostgres(appsPg, defaultScanValues),
    ...spliceInstanceNames,
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
      TARGET_HOSTNAME: CLUSTER_HOSTNAME,
      OPERATOR_WALLET_USER_ID: validatorWalletUserName,
      OIDC_AUTHORITY_URL: auth0Client.getCfg().auth0Domain,
      TRUSTED_SCAN_URL: `http://scan-app.${xns.logicalName}:5012`,
      YOUR_CONTACT_POINT: daContactPoint,
    }),
    ...loadYamlFromFile(
      `${REPO_ROOT}/apps/app/src/pack/examples/sv-helm/sv-validator-values.yaml`,
      {
        TARGET_HOSTNAME: CLUSTER_HOSTNAME,
        MIGRATION_ID: decentralizedSynchronizerMigrationConfig.active.migrationId.toString(),
        YOUR_SV_NAME: onboardingName,
      }
    ),
    metrics: {
      enable: true,
    },
    participantIdentitiesDumpPeriodicBackup: backupConfig,
    ...spliceInstanceNames,
  };

  const validatorValuesWithSpecifiedAud: ChartValues = {
    ...validatorValues,
    ...persistenceForPostgres(appsPg, validatorValues),
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
    defaultVersion,
    imagePullDeps
      .concat([sv, participant])
      .concat([svValidatorAppSecret, svValidatorUISecret])
      .concat([cnsUiSecret(xns, auth0Client, cnsUiClientId)])
      .concat(backupConfigSecret ? [backupConfigSecret] : [])
      .concat([appsPg])
  );

  return { sv, validator };
}
