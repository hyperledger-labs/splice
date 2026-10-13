// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';
import {
  Auth0Client,
  BucketConfig,
  ChartValues,
  CLUSTER_BASENAME,
  CLUSTER_HOSTNAME,
  clusterSmallDisk,
  CnInput,
  config,
  daContactPoint,
  DecentralizedSynchronizerUpgradeConfig,
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
  installValidatorSecrets,
  ValidatorTopupConfig,
  InstalledHelmChart,
  ansDomainPrefix,
  failOnAppVersionMismatch,
  networkWideConfig,
  getValidatorAppApiAudience,
  getNamespaceConfig,
  persistentHeapDumpsPvc,
  standardStorageClassName,
  pvcSuffix,
  CnChartVersion,
} from '@canton-network/splice-pulumi-common';
import { installLoopback } from '@canton-network/splice-pulumi-common-sv';
import {
  installParticipant,
  installWalletGateway,
} from '@canton-network/splice-pulumi-common-validator';
import {
  installPasswordWithParent,
  SplicePostgres,
} from '@canton-network/splice-pulumi-common/src/postgres';

import { installPartyAllocator } from './partyAllocator';
import { validatorConfig, validatorName } from './validatorConfig';

type BootstrapCliConfig = {
  cluster: string;
  date: string;
};

const bootstrappingConfig: BootstrapCliConfig = config.optionalEnv('BOOTSTRAPPING_CONFIG')
  ? JSON.parse(config.requireEnv('BOOTSTRAPPING_CONFIG'))
  : undefined;

const participantIdentitiesFile = config.optionalEnv('PARTICIPANT_IDENTITIES_FILE');

const validatorVersion: CnChartVersion = validatorConfig.version
  ? CnChartVersion.parse(validatorConfig.version)
  : activeVersion;

export async function installNode(auth0Client: Auth0Client): Promise<void> {
  await pulumi.log.info(
    validatorVersion.type === 'local'
      ? 'Using locally built charts by default'
      : `Using charts from the ghcr by default, version ${validatorVersion.version}`
  );

  const xns = exactNamespace(validatorConfig.namespace, true);

  auth0Client.reuseNamespaceConfig('validator', xns.logicalName);

  const { participantBootstrapDumpSecret, backupConfigSecret, backupConfig } =
    await setupBootstrapping({
      xns,
      namespace: validatorConfig.namespace,
      CLUSTER_BASENAME,
      participantIdentitiesFile,
      bootstrappingConfig,
    });

  const onboardingSecret =
    preApproveValidatorRunbook || validatorName != 'validator-runbook'
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
      ingress: {
        walletGateway: validatorConfig.walletGateway.enabled,
      },
    },
    validatorVersion,
    { dependsOn: ingressImagePullDeps.concat([validator]) }
  );
}

type ValidatorDeploymentConfig = {
  auth0Client: Auth0Client;
  xns: ExactNamespace;
  onboardingSecret?: string;
  backupConfig?: BucketConfig;
  participantBootstrapDumpSecret?: pulumi.Resource;
  topupConfig?: ValidatorTopupConfig;
  imagePullDeps: CnInput<pulumi.Resource>[];
  otherDeps: CnInput<pulumi.Resource>[];
  loopback: pulumi.Resource[] | null;
  backupConfigSecret?: pulumi.Resource;
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

  const participantPruningConfig = validatorConfig?.participantPruningSchedule;

  const supportsValidatorRunbookReset = config.envFlag('SUPPORTS_VALIDATOR_RUNBOOK_RESET', false);
  const postgresValuesFromFile: ChartValues = loadYamlFromFile(
    `${SPLICE_ROOT}/apps/app/src/pack/examples/sv-helm/postgres-values-validator-participant.yaml`
  );
  const postgresValues: ChartValues = validatorConfig.postgresPvcSize
    ? {
        ...postgresValuesFromFile,
        db: { ...postgresValuesFromFile.db, volumeSize: validatorConfig.postgresPvcSize },
      }
    : postgresValuesFromFile;
  const postgresInstanceName = 'postgres';
  const postgres = new SplicePostgres(
    xns,
    postgresInstanceName,
    parent => installPasswordWithParent(parent, xns, postgresInstanceName, 'postgres-secrets'),
    // No need to support legacy chart
    {
      deployment: 'docker-image',
      postgresImage: postgresValuesFromFile.db.postgresImage || 'postgres:18',
    },
    postgresValues,
    true,
    supportsValidatorRunbookReset,
    validatorVersion
  );
  const participantAddress = (
    await installParticipant(
      validatorConfig,
      xns,
      auth0Client.getCfg(),
      false, // We don't currently support non-auth for validator-runbook
      validatorVersion,
      postgres,
      {
        dependsOn: imagePullDeps.concat([postgres]),
      }
    )
  ).participantAddress;

  const fixedTokensValue: ChartValues = {
    cluster: {
      fixedTokens: true,
    },
  };

  const validatorSecrets = await installValidatorSecrets(xns, auth0Client);

  const validatorValuesFromYamlFiles = {
    ...loadYamlFromFile(`${SPLICE_ROOT}/apps/app/src/pack/examples/sv-helm/validator-values.yaml`, {
      TARGET_HOSTNAME: CLUSTER_HOSTNAME,
      OPERATOR_WALLET_USER_ID: validatorConfig.operatorWalletUserId,
      OIDC_AUTHORITY_URL: auth0Client.getCfg().auth0Domain,
      YOUR_CONTACT_POINT: daContactPoint,
    }),
    ...loadYamlFromFile(
      `${SPLICE_ROOT}/apps/app/src/pack/examples/sv-helm/standalone-validator-values.yaml`,
      {
        MIGRATION_ID: DecentralizedSynchronizerUpgradeConfig.activeMigrationId.toString(),
        SPONSOR_SV_URL: `https://sv.sv-2.${CLUSTER_HOSTNAME}`,
        YOUR_VALIDATOR_NODE_NAME: validatorConfig.nodeIdentifier || validatorConfig.partyHint,
        TRUSTED_SCAN_URL: `https://scan.sv-2.${CLUSTER_HOSTNAME}`,
      }
    ),
  };

  const newParticipantIdentifier =
    validatorConfig.newParticipantId ||
    validatorValuesFromYamlFiles?.participantIdentitiesDumpImport?.newParticipantIdentifier;

  const validatorValues: ChartValues = {
    ...validatorValuesFromYamlFiles,
    scanClient: validatorConfig.validatorApp?.scanClient ?? validatorValuesFromYamlFiles.scanClient,
    synchronizer:
      validatorConfig.validatorApp?.synchronizer ?? validatorValuesFromYamlFiles.synchronizer,
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
    participantPruningSchedule: participantPruningConfig,
    ...(participantBootstrapDumpSecret ? { nodeIdentifier: newParticipantIdentifier } : {}),
    persistence: {
      ...validatorValuesFromYamlFiles.persistence,
      postgresName: postgres.instanceName,
      host: postgres.address,
    },
    pvc: {
      volumeStorageClass: standardStorageClassName,
      volumeName: `domain-migration-validator-${pvcSuffix}`,
    },
    persistentDataPvc: persistentHeapDumpsPvc(),
    db: { volumeSize: clusterSmallDisk ? '240Gi' : undefined },
    enablePostgresMetrics: true,
    ...spliceInstanceNames,
    maxVettingDelay: networkWideConfig?.maxVettingDelay,
    additionalEnvVars: validatorConfig.validatorApp?.additionalEnvVars,
    additionalJvmOptions: validatorConfig.validatorApp?.additionalJvmOptions,
    resources: validatorConfig.validatorApp?.resources,
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
      audience: getValidatorAppApiAudience(auth0Client.getCfg(), xns.logicalName),
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

  const cnsUiClientId = getNamespaceConfig(auth0Client.getCfg(), xns.logicalName).uiClientIds.cns;
  if (!cnsUiClientId) {
    throw new Error('No validator ui client id in auth0 config');
  }
  const dependsOn = imagePullDeps
    .concat(loopback ? loopback : [])
    .concat(validatorSecrets)
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
    validatorVersion,
    { dependsOn: dependsOn }
  );
  if (validatorConfig?.partyAllocator.enable) {
    installPartyAllocator(xns, validatorConfig.partyAllocator, [validatorChart]);
  }
  if (validatorConfig.walletGateway.enabled) {
    await installWalletGateway(
      auth0Client,
      xns,
      validatorConfig.walletGateway,
      participantAddress,
      postgres,
      validatorConfig.logging.level,
      [validatorChart]
    );
  }
  return validatorChart;
}
