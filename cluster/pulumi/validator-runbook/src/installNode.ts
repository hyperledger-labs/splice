import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import * as random from '@pulumi/random';
import {
  Auth0Client,
  BackupConfig,
  ChartValues,
  CnInput,
  ansUiSecret,
  exactNamespace,
  ExactNamespace,
  fixedTokens,
  GlobalDomainMigrationConfig,
  imagePullSecret,
  imagePullSecretByNamespaceName,
  installCNRunbookHelmChart,
  installCNRunbookHelmChartByNamespaceName,
  installLoopback,
  installPostgresPasswordSecret,
  installValidatorOnboardingSecret,
  loadYamlFromFile,
  REPO_ROOT,
  setupBootstrapping,
  validatorSecrets,
  ValidatorTopupConfig,
  nonSvValidatorTopupConfig,
  defaultVersion,
} from 'cn-pulumi-common';
import { failOnAppVersionMismatch } from 'cn-pulumi-common/src/upgrades';

import {
  CLUSTER_BASENAME,
  TARGET_CLUSTER,
  VALIDATOR_NAMESPACE as RUNBOOK_NAMESPACE,
} from './utils';

type BootstrapCliConfig = {
  cluster: string;
  date: string;
};

const bootstrappingConfig: BootstrapCliConfig = process.env.BOOTSTRAPPING_CONFIG
  ? JSON.parse(process.env.BOOTSTRAPPING_CONFIG)
  : undefined;

const participantIdentitiesFile = process.env.PARTICIPANT_IDENTITIES_FILE;

const VALIDATOR_WALLET_USER_ID =
  process.env.VALIDATOR_WALLET_USER_ID || 'auth0|6526fab5214c99a9a8e1e3cc'; // Default to admin@validator.com at the validator-test tenant by default
const DEFAULT_AUDIENCE = 'https://canton.network.global';

const globalDomainMigrationConfig = GlobalDomainMigrationConfig.fromEnv();

export async function installNode(auth0Client: Auth0Client): Promise<void> {
  console.error(
    defaultVersion.type === 'local'
      ? 'Using locally built charts by default'
      : `Using charts from the artifactory by default, version ${defaultVersion.version}`
  );
  console.error(`TARGET_CLUSTER: ${TARGET_CLUSTER}`);
  console.error(`Installing validator node in namespace: ${RUNBOOK_NAMESPACE}`);

  const xns = exactNamespace(RUNBOOK_NAMESPACE, true);

  const { participantBootstrapDumpSecret, backupConfigSecret, backupConfig } =
    await setupBootstrapping({
      xns,
      RUNBOOK_NAMESPACE,
      CLUSTER_BASENAME,
      participantIdentitiesFile,
      bootstrappingConfig,
    });

  const onboardingSecret = 'validatorsecret';

  const loopback =
    TARGET_CLUSTER === CLUSTER_BASENAME
      ? installLoopback(xns, CLUSTER_BASENAME, defaultVersion)
      : null;

  // For the runbooks, we pull images from artifactory when using remote charts, and need creds for that
  const imagePullDeps = defaultVersion.type === 'local' ? [] : imagePullSecret(xns);

  const password = new random.RandomPassword(`${xns.logicalName}-postgres-passwd`, {
    length: 16,
    overrideSpecial: '_%@',
    special: true,
  }).result;
  const passwordSecret = installPostgresPasswordSecret(xns, password, 'postgres-secrets');

  const validator = await installValidator({
    xns,
    onboardingSecret,
    participantBootstrapDumpSecret,
    auth0Client,
    imagePullDeps,
    loopback,
    backupConfigSecret,
    backupConfig,
    topupConfig: nonSvValidatorTopupConfig,
    otherDeps: [passwordSecret],
  });

  // For the runbooks, we pull images from artifactory when using remote charts, and need creds for that
  const ingressImagePullDeps =
    defaultVersion.type === 'local' ? [] : imagePullSecretByNamespaceName('cluster-ingress');
  installCNRunbookHelmChartByNamespaceName(
    xns.ns.metadata.name,
    xns.logicalName,
    'cluster-ingress-validator',
    'cn-cluster-ingress-runbook',
    {
      cluster: {
        hostPrefix: '',
        hostname: `${CLUSTER_BASENAME}.network.canton.global`,
        svNamespace: RUNBOOK_NAMESPACE,
      },
      withSvIngress: false,
    },
    defaultVersion,
    ingressImagePullDeps.concat([validator])
  );
}

type ValidatorConfig = {
  auth0Client: Auth0Client;
  xns: ExactNamespace;
  onboardingSecret?: string;
  backupConfig?: BackupConfig;
  participantBootstrapDumpSecret?: pulumi.Resource;
  topupConfig?: ValidatorTopupConfig;
  imagePullDeps: CnInput<pulumi.Resource>[];
  otherDeps: CnInput<pulumi.Resource>[];
  loopback: k8s.helm.v3.Release | null;
  backupConfigSecret?: pulumi.Resource;
};

async function installValidator(config: ValidatorConfig): Promise<k8s.helm.v3.Release> {
  const {
    xns,
    onboardingSecret,
    participantBootstrapDumpSecret,
    auth0Client,
    imagePullDeps,
    otherDeps,
    loopback,
    backupConfigSecret,
    backupConfig,
    topupConfig,
  } = config;

  const postgres = installCNRunbookHelmChart(
    xns,
    'postgres',
    'cn-postgres',
    {},
    defaultVersion,
    otherDeps
  );

  const participantValues: ChartValues = {
    ...loadYamlFromFile(`${REPO_ROOT}/apps/app/src/pack/examples/sv-helm/participant-values.yaml`, {
      OIDC_AUTHORITY_URL: auth0Client.getCfg().auth0Domain,
      YOUR_NODE_NAME: 'validator-runbook',
    }),
    ...loadYamlFromFile(
      `${REPO_ROOT}/apps/app/src/pack/examples/sv-helm/standalone-participant-values.yaml`,
      { MIGRATION_ID: globalDomainMigrationConfig.active.migrationId.toString() }
    ),
    metrics: {
      enable: true,
    },
    disableAutoInit:
      !!participantBootstrapDumpSecret || globalDomainMigrationConfig.isRunningMigration(),
  };

  const participantValuesWithSpecifiedAud: ChartValues = {
    ...participantValues,
    auth: {
      ...participantValues.auth,
      targetAudience: auth0Client.getCfg().appToApiAudience['participant'] || DEFAULT_AUDIENCE,
    },
  };

  const participant = installCNRunbookHelmChart(
    xns,
    'participant',
    'cn-participant',
    participantValuesWithSpecifiedAud,
    defaultVersion,
    imagePullDeps.concat([postgres]).concat(loopback !== null ? loopback : [])
  );

  const fixedTokensValue: ChartValues = {
    cluster: {
      fixedTokens: true,
    },
  };

  const validatorNameSpaceAuth0Clients = auth0Client.getCfg().namespaceToUiToClientId['validator'];
  if (!validatorNameSpaceAuth0Clients) {
    throw new Error('No validator namespace in auth0 config');
  }
  const walletUiClientId = validatorNameSpaceAuth0Clients['wallet'];
  if (!walletUiClientId) {
    throw new Error('No wallet ui client id in auth0 config');
  }

  const { appSecret: validatorAppSecret, uiSecret: validatorUISecret } = await validatorSecrets(
    xns,
    auth0Client,
    walletUiClientId
  );

  const validatorValuesFromYamlFiles = {
    ...loadYamlFromFile(`${REPO_ROOT}/apps/app/src/pack/examples/sv-helm/validator-values.yaml`, {
      TARGET_CLUSTER: TARGET_CLUSTER,
      OPERATOR_WALLET_USER_ID: VALIDATOR_WALLET_USER_ID,
      OIDC_AUTHORITY_URL: auth0Client.getCfg().auth0Domain,
      TRUSTED_SCAN_URL: `https://scan.sv-1.svc.${CLUSTER_BASENAME}.network.canton.global`,
    }),
    ...loadYamlFromFile(
      `${REPO_ROOT}/apps/app/src/pack/examples/sv-helm/standalone-validator-values.yaml`,
      {
        MIGRATION_ID: globalDomainMigrationConfig.active.migrationId.toString(),
        SPONSOR_SV_URL: `https://sv.sv-1.svc.${CLUSTER_BASENAME}.network.canton.global`,
      }
    ),
  };
  const validatorValues: ChartValues = {
    ...validatorValuesFromYamlFiles,
    migration: {
      ...validatorValuesFromYamlFiles.migration,
      migrating: globalDomainMigrationConfig.isRunningMigration()
        ? true
        : validatorValuesFromYamlFiles.migration.migrating,
    },
    metrics: {
      enable: true,
    },
    participantIdentitiesDumpPeriodicBackup: backupConfig,
    failOnAppVersionMismatch: failOnAppVersionMismatch(),
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

  const ansUiClientId = validatorNameSpaceAuth0Clients['ans'];
  if (!ansUiClientId) {
    throw new Error('No validator ui client id in auth0 config');
  }
  const dependsOn = imagePullDeps
    .concat([participant])
    .concat([validatorAppSecret, validatorUISecret])
    .concat([ansUiSecret(xns, auth0Client, ansUiClientId)])
    .concat(backupConfigSecret ? [backupConfigSecret] : [])
    .concat(
      onboardingSecret ? [installValidatorOnboardingSecret(xns, 'validator', onboardingSecret)] : []
    )
    .concat(participantBootstrapDumpSecret ? [participantBootstrapDumpSecret] : []);

  return installCNRunbookHelmChart(
    xns,
    'validator',
    'cn-validator',
    validatorValuesWithMaybeTopups,
    defaultVersion,
    dependsOn
  );
}
