// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';
import {
  Auth0Client,
  BackupConfig,
  ChartValues,
  CLUSTER_BASENAME,
  CLUSTER_HOSTNAME,
  clusterSmallDisk,
  CnInput,
  cnsUiSecret,
  config,
  daContactPoint,
  DecentralizedSynchronizerUpgradeConfig,
  DEFAULT_AUDIENCE,
  activeVersion,
  exactNamespace,
  ExactNamespace,
  fixedTokens,
  imagePullSecret,
  imagePullSecretByNamespaceName,
  installSpliceRunbookHelmChart,
  installSpliceRunbookHelmChartByNamespaceName,
  installValidatorOnboardingSecret,
  isDevNet,
  loadYamlFromFile,
  nonDevNetNonSvValidatorTopupConfig,
  nonSvValidatorTopupConfig,
  participantBootstrapDumpSecretName,
  preApproveValidatorRunbook,
  SPLICE_ROOT,
  setupBootstrapping,
  spliceInstanceNames,
  validatorSecrets,
  ValidatorTopupConfig,
  InstalledHelmChart,
  ansDomainPrefix,
  failOnAppVersionMismatch,
  networkWideConfig,
} from '@lfdecentralizedtrust/splice-pulumi-common';
import { installLoopback } from '@lfdecentralizedtrust/splice-pulumi-common-sv';
import { installParticipant } from '@lfdecentralizedtrust/splice-pulumi-common-validator';
import { SplicePostgres } from '@lfdecentralizedtrust/splice-pulumi-common/src/postgres';
import _ from 'lodash';

import { installPartyAllocator } from './partyAllocator';
import { validatorConfig } from './validatorConfig';

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

export async function installNode(auth0Client: Auth0Client): Promise<void> {
  console.error(
    activeVersion.type === 'local'
      ? 'Using locally built charts by default'
      : `Using charts from the artifactory by default, version ${activeVersion.version}`
  );

  const xns = exactNamespace(validatorConfig.namespace, true);

  const { participantBootstrapDumpSecret, backupConfigSecret, backupConfig } =
    await setupBootstrapping({
      xns,
      namespace: validatorConfig.namespace,
      CLUSTER_BASENAME,
      participantIdentitiesFile,
      bootstrappingConfig,
    });

  const onboardingSecret = preApproveValidatorRunbook
    ? validatorConfig.onboardingSecret
    : undefined;

  const loopback = installLoopback(xns);

  const imagePullDeps = imagePullSecret(xns);

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
    otherDeps: [],
    nodeIdentifier: 'validator-runbook',
  });

  const ingressImagePullDeps = imagePullSecretByNamespaceName('cluster-ingress');
  installSpliceRunbookHelmChartByNamespaceName(
    xns.ns.metadata.name,
    xns.logicalName,
    'cluster-ingress-validator',
    'splice-cluster-ingress-runbook',
    {
      cluster: {
        hostname: CLUSTER_HOSTNAME,
        svNamespace: validatorConfig.namespace,
      },
      spliceDomainNames: {
        nameServiceDomain: ansDomainPrefix,
      },
      withSvIngress: false,
    },
    activeVersion,
    { dependsOn: ingressImagePullDeps.concat([validator]) }
  );
}

type ValidatorDeploymentConfig = {
  auth0Client: Auth0Client;
  xns: ExactNamespace;
  onboardingSecret?: string;
  backupConfig?: BackupConfig;
  participantBootstrapDumpSecret?: pulumi.Resource;
  topupConfig?: ValidatorTopupConfig;
  imagePullDeps: CnInput<pulumi.Resource>[];
  otherDeps: CnInput<pulumi.Resource>[];
  loopback: pulumi.Resource[] | null;
  backupConfigSecret?: pulumi.Resource;
  nodeIdentifier: string;
};

async function installValidator(
  validatorDeploymentConfig: ValidatorDeploymentConfig
): Promise<InstalledHelmChart> {
  const {
    xns,
    onboardingSecret,
    participantBootstrapDumpSecret,
    auth0Client,
    loopback,
    imagePullDeps,
    backupConfigSecret,
    backupConfig,
    topupConfig,
  } = validatorDeploymentConfig;

  // TODO(DACH-NY/canton-network-node#14679): Remove the override once ciperiodic has been bumped to 0.2.0
  const postgresPvcSizeOverride = config.optionalEnv('VALIDATOR_RUNBOOK_POSTGRES_PVC_SIZE');
  const supportsValidatorRunbookReset = config.envFlag('SUPPORTS_VALIDATOR_RUNBOOK_RESET', false);
  const postgresValues: ChartValues = _.merge(
    loadYamlFromFile(
      `${SPLICE_ROOT}/apps/app/src/pack/examples/sv-helm/postgres-values-validator-participant.yaml`
    ),
    { db: { volumeSize: postgresPvcSizeOverride } }
  );
  const postgres = new SplicePostgres(
    xns,
    'postgres',
    // can be removed once base version > 0.2.1
    `postgres`,
    'postgres-secrets',
    postgresValues,
    true,
    supportsValidatorRunbookReset
  );
  const participantAddress = installParticipant(
    validatorConfig,
    DecentralizedSynchronizerUpgradeConfig.active.id,
    xns,
    auth0Client.getCfg(),
    validatorDeploymentConfig.nodeIdentifier,
    activeVersion,
    postgres,
    {
      dependsOn: imagePullDeps.concat([postgres]),
    }
  ).participantAddress;

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
    ...loadYamlFromFile(`${SPLICE_ROOT}/apps/app/src/pack/examples/sv-helm/validator-values.yaml`, {
      TARGET_HOSTNAME: CLUSTER_HOSTNAME,
      OPERATOR_WALLET_USER_ID: VALIDATOR_WALLET_USER_ID,
      OIDC_AUTHORITY_URL: auth0Client.getCfg().auth0Domain,
      TRUSTED_SCAN_URL: `https://scan.sv-2.${CLUSTER_HOSTNAME}`,
      YOUR_CONTACT_POINT: daContactPoint,
    }),
    ...loadYamlFromFile(
      `${SPLICE_ROOT}/apps/app/src/pack/examples/sv-helm/standalone-validator-values.yaml`,
      {
        MIGRATION_ID: DecentralizedSynchronizerUpgradeConfig.active.id.toString(),
        SPONSOR_SV_URL: `https://sv.sv-2.${CLUSTER_HOSTNAME}`,
        YOUR_VALIDATOR_NODE_NAME: validatorDeploymentConfig.nodeIdentifier,
      }
    ),
  };

  const newParticipantIdentifier =
    validatorConfig.newParticipantId ||
    validatorValuesFromYamlFiles?.participantIdentitiesDumpImport?.newParticipantIdentifier;

  const validatorValues: ChartValues = {
    ...validatorValuesFromYamlFiles,
    migration: {
      ...validatorValuesFromYamlFiles.migration,
      migrating: DecentralizedSynchronizerUpgradeConfig.isRunningMigration()
        ? true
        : validatorValuesFromYamlFiles.migration.migrating,
    },
    metrics: {
      enable: true,
    },
    participantAddress,
    participantIdentitiesDumpPeriodicBackup: backupConfig,
    failOnAppVersionMismatch: failOnAppVersionMismatch,
    validatorPartyHint: validatorConfig.partyHint,
    migrateValidatorParty: validatorConfig.migrateParty,
    participantIdentitiesDumpImport: participantBootstrapDumpSecret
      ? {
          secretName: participantBootstrapDumpSecretName,
          newParticipantIdentifier,
        }
      : undefined,
    ...(participantBootstrapDumpSecret ? { nodeIdentifier: newParticipantIdentifier } : {}),
    persistence: {
      ...validatorValuesFromYamlFiles.persistence,
      postgresName: 'postgres',
    },
    db: { volumeSize: clusterSmallDisk ? '240Gi' : undefined },
    enablePostgresMetrics: true,
    ...spliceInstanceNames,
    maxVettingDelay: networkWideConfig?.maxVettingDelay,
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
    .concat(loopback ? loopback : [])
    .concat([validatorAppSecret, validatorUISecret])
    .concat([cnsUiSecret(xns, auth0Client, cnsUiClientId)])
    .concat(backupConfigSecret ? [backupConfigSecret] : [])
    .concat(
      onboardingSecret ? [installValidatorOnboardingSecret(xns, 'validator', onboardingSecret)] : []
    )
    .concat(participantBootstrapDumpSecret ? [participantBootstrapDumpSecret] : []);

  const validatorChart = installSpliceRunbookHelmChart(
    xns,
    'validator',
    'splice-validator',
    validatorValuesWithMaybeTopups,
    activeVersion,
    { dependsOn: dependsOn }
  );
  if (validatorConfig?.partyAllocator.enable) {
    installPartyAllocator(xns, [validatorChart]);
  }
  return validatorChart;
}
