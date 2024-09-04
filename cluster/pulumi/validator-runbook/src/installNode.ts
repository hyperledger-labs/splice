import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import * as random from '@pulumi/random';
import _ from 'lodash';
import {
  Auth0Client,
  BackupConfig,
  ChartValues,
  CnInput,
  cnsUiSecret,
  exactNamespace,
  ExactNamespace,
  fixedTokens,
  DecentralizedSynchronizerMigrationConfig,
  imagePullSecret,
  imagePullSecretByNamespaceName,
  installSpliceRunbookHelmChart,
  installSpliceRunbookHelmChartByNamespaceName,
  installLoopback,
  installPostgresPasswordSecret,
  installValidatorOnboardingSecret,
  loadYamlFromFile,
  REPO_ROOT,
  CLUSTER_BASENAME,
  CLUSTER_HOSTNAME,
  setupBootstrapping,
  validatorSecrets,
  isDevNet,
  ValidatorTopupConfig,
  nonSvValidatorTopupConfig,
  nonDevNetNonSvValidatorTopupConfig,
  defaultVersion,
  participantBootstrapDumpSecretName,
  config,
  preApproveValidatorRunbook,
  clusterSmallDisk,
  daContactPoint,
  spliceInstanceNames,
  autoInitValues,
} from 'splice-pulumi-common';
import { failOnAppVersionMismatch } from 'splice-pulumi-common/src/upgrades';

import {
  VALIDATOR_NAMESPACE as RUNBOOK_NAMESPACE,
  VALIDATOR_PARTY_HINT,
  VALIDATOR_MIGRATE_PARTY,
  VALIDATOR_NEW_PARTICIPANT_ID,
} from './utils';

type BootstrapCliConfig = {
  cluster: string;
  date: string;
};

const bootstrappingConfig: BootstrapCliConfig = config.optionalEnv('BOOTSTRAPPING_CONFIG')
  ? JSON.parse(config.requireEnv('BOOTSTRAPPING_CONFIG'))
  : undefined;

const participantIdentitiesFile = config.optionalEnv('PARTICIPANT_IDENTITIES_FILE');

const VALIDATOR_WALLET_USER_ID =
  config.optionalEnv('VALIDATOR_WALLET_USER_ID') || 'auth0|6526fab5214c99a9a8e1e3cc'; // Default to admin@validator.com at the validator-test tenant by default
const DEFAULT_AUDIENCE = 'https://canton.network.global';

const decentralizedSynchronizerMigrationConfig = DecentralizedSynchronizerMigrationConfig.fromEnv();

export async function installNode(auth0Client: Auth0Client): Promise<void> {
  console.error(
    defaultVersion.type === 'local'
      ? 'Using locally built charts by default'
      : `Using charts from the artifactory by default, version ${defaultVersion.version}`
  );
  console.error(`CLUSTER_HOSTNAME: ${CLUSTER_HOSTNAME}`);
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

  const onboardingSecret = preApproveValidatorRunbook ? 'validatorsecret' : undefined;

  const loopback = installLoopback(xns, CLUSTER_HOSTNAME, defaultVersion);

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
    topupConfig: isDevNet ? nonSvValidatorTopupConfig : nonDevNetNonSvValidatorTopupConfig,
    otherDeps: [passwordSecret],
    nodeIdentifier: 'validator-runbook',
  });

  // For the runbooks, we pull images from artifactory when using remote charts, and need creds for that
  const ingressImagePullDeps =
    defaultVersion.type === 'local' ? [] : imagePullSecretByNamespaceName('cluster-ingress');
  installSpliceRunbookHelmChartByNamespaceName(
    xns.ns.metadata.name,
    xns.logicalName,
    'cluster-ingress-validator',
    'cn-cluster-ingress-runbook',
    {
      cluster: {
        hostname: CLUSTER_HOSTNAME,
        svNamespace: RUNBOOK_NAMESPACE,
      },
      withSvIngress: false,
    },
    defaultVersion,
    { dependsOn: ingressImagePullDeps.concat([validator]) }
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
  nodeIdentifier: string;
};

async function installValidator(validatorConfig: ValidatorConfig): Promise<k8s.helm.v3.Release> {
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
  } = validatorConfig;

  // TODO(#14507): Remove the override once ciperiodic has been bumped to 0.2.0
  const postgresPvcSizeOverride = config.optionalEnv('VALIDATOR_RUNBOOK_POSTGRES_PVC_SIZE');
  const postgresValues: ChartValues = _.merge(
    loadYamlFromFile(
      `${REPO_ROOT}/apps/app/src/pack/examples/sv-helm/postgres-values-validator-participant.yaml`
    ),
    { db: { volumeSize: postgresPvcSizeOverride || (clusterSmallDisk ? '240Gi' : undefined) } }
  );
  const postgres = installSpliceRunbookHelmChart(
    xns,
    'postgres',
    'cn-postgres',
    postgresValues,
    defaultVersion,
    {
      dependsOn: otherDeps,
    }
  );

  const participantValues: ChartValues = {
    ...loadYamlFromFile(`${REPO_ROOT}/apps/app/src/pack/examples/sv-helm/participant-values.yaml`, {
      OIDC_AUTHORITY_URL: auth0Client.getCfg().auth0Domain,
    }),
    ...loadYamlFromFile(
      `${REPO_ROOT}/apps/app/src/pack/examples/sv-helm/standalone-participant-values.yaml`,
      { MIGRATION_ID: decentralizedSynchronizerMigrationConfig.active.migrationId.toString() }
    ),
    metrics: {
      enable: true,
    },
  };

  const participantValuesWithSpecifiedAud: ChartValues = {
    ...participantValues,
    auth: {
      ...participantValues.auth,
      targetAudience: auth0Client.getCfg().appToApiAudience['participant'] || DEFAULT_AUDIENCE,
    },
    persistence: {
      ...participantValues.persistence,
      postgresName: 'postgres',
    },
    enablePostgresMetrics: true,
    ...autoInitValues('cn-participant', defaultVersion, validatorConfig.nodeIdentifier),
  };

  const participant = installSpliceRunbookHelmChart(
    xns,
    'participant',
    'cn-participant',
    participantValuesWithSpecifiedAud,
    defaultVersion,
    { dependsOn: imagePullDeps.concat([postgres]).concat(loopback !== null ? loopback : []) }
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
      TARGET_HOSTNAME: CLUSTER_HOSTNAME,
      OPERATOR_WALLET_USER_ID: VALIDATOR_WALLET_USER_ID,
      OIDC_AUTHORITY_URL: auth0Client.getCfg().auth0Domain,
      TRUSTED_SCAN_URL: `https://scan.sv-2.${CLUSTER_HOSTNAME}`,
      YOUR_CONTACT_POINT: daContactPoint,
    }),
    ...loadYamlFromFile(
      `${REPO_ROOT}/apps/app/src/pack/examples/sv-helm/standalone-validator-values.yaml`,
      {
        MIGRATION_ID: decentralizedSynchronizerMigrationConfig.active.migrationId.toString(),
        SPONSOR_SV_URL: `https://sv.sv-2.${CLUSTER_HOSTNAME}`,
        YOUR_VALIDATOR_NAME: validatorConfig.nodeIdentifier,
      }
    ),
  };

  const validatorValues: ChartValues = {
    ...validatorValuesFromYamlFiles,
    migration: {
      ...validatorValuesFromYamlFiles.migration,
      migrating: decentralizedSynchronizerMigrationConfig.isRunningMigration()
        ? true
        : validatorValuesFromYamlFiles.migration.migrating,
    },
    metrics: {
      enable: true,
    },
    participantIdentitiesDumpPeriodicBackup: backupConfig,
    failOnAppVersionMismatch: failOnAppVersionMismatch(),
    validatorPartyHint: VALIDATOR_PARTY_HINT || 'digitalasset-testValidator-1',
    migrateValidatorParty: VALIDATOR_MIGRATE_PARTY,
    participantIdentitiesDumpImport: participantBootstrapDumpSecret
      ? {
          secretName: participantBootstrapDumpSecretName,
          newParticipantIdentifier:
            VALIDATOR_NEW_PARTICIPANT_ID ||
            validatorValuesFromYamlFiles?.participantIdentitiesDumpImport?.newParticipantIdentifier,
        }
      : undefined,
    persistence: {
      ...validatorValuesFromYamlFiles.persistence,
      postgresName: 'postgres',
    },
    db: { volumeSize: clusterSmallDisk ? '240Gi' : undefined },
    enablePostgresMetrics: true,
    ...spliceInstanceNames,
  };

  const validatorValuesWithOnboardingOverride = onboardingSecret
    ? validatorValues
    : {
        ...validatorValues,
        // Get a new secret from sv-1 instead of the configured one.
        // This works only when validator-runbook is deployed on devnet-like clusters.
        onboardingSecretFrom: undefined,
      };

  const validatorValuesWithSpecifiedAud: ChartValues = {
    ...validatorValuesWithOnboardingOverride,
    auth: {
      ...validatorValuesWithOnboardingOverride.auth,
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

  const cnsUiClientId = validatorNameSpaceAuth0Clients['cns'];
  if (!cnsUiClientId) {
    throw new Error('No validator ui client id in auth0 config');
  }
  const dependsOn = imagePullDeps
    .concat([participant])
    .concat([validatorAppSecret, validatorUISecret])
    .concat([cnsUiSecret(xns, auth0Client, cnsUiClientId)])
    .concat(backupConfigSecret ? [backupConfigSecret] : [])
    .concat(
      onboardingSecret ? [installValidatorOnboardingSecret(xns, 'validator', onboardingSecret)] : []
    )
    .concat(participantBootstrapDumpSecret ? [participantBootstrapDumpSecret] : []);

  return installSpliceRunbookHelmChart(
    xns,
    'validator',
    'cn-validator',
    validatorValuesWithMaybeTopups,
    defaultVersion,
    { dependsOn: dependsOn }
  );
}
