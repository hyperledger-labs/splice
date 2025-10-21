// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import {
  activeVersion,
  Auth0Client,
  BackupConfig,
  BootstrappingDumpConfig,
  CLUSTER_BASENAME,
  CnInput,
  config,
  daContactPoint,
  DEFAULT_AUDIENCE,
  DomainMigrationIndex,
  ExactNamespace,
  failOnAppVersionMismatch,
  fetchAndInstallParticipantBootstrapDump,
  getAdditionalJvmOptions,
  installAuth0Secret,
  installAuth0UISecret,
  installBootstrapDataBucketSecret,
  installSpliceHelmChart,
  installValidatorOnboardingSecret,
  K8sResourceSchema,
  LogLevel,
  networkWideConfig,
  participantBootstrapDumpSecretName,
  ParticipantPruningConfig,
  PersistenceConfig,
  spliceInstanceNames,
  validatorOnboardingSecretName,
  ValidatorTopupConfig,
} from '@lfdecentralizedtrust/splice-pulumi-common';
import { Secret } from '@pulumi/kubernetes/core/v1';
import { Output } from '@pulumi/pulumi';

import { SweepConfig } from './sweep';

export type ExtraDomain = {
  alias: string;
  url: string;
};

export type ValidatorBackupConfig = {
  // If not set the secret will be created.
  secret?: pulumi.Resource;
  config: BackupConfig;
};

export type ValidatorSecrets = {
  validatorSecret: Secret;
  legacyValidatorSecret?: Secret;
  wallet: Secret;
  cns: Secret;
  auth0Client: Auth0Client;
};

type BasicValidatorConfig = {
  xns: ExactNamespace;
  topupConfig?: ValidatorTopupConfig;
  validatorWalletUsers: Output<string[]>;
  disableAllocateLedgerApiUserParty?: boolean;
  backupConfig?: ValidatorBackupConfig;
  extraDependsOn?: CnInput<pulumi.Resource>[];
  scanAddress: Output<string> | string;
  persistenceConfig: PersistenceConfig;
  appDars?: string[];
  validatorPartyHint?: string;
  extraDomains?: ExtraDomain[];
  additionalConfig?: string;
  additionalUsers?: k8s.types.input.core.v1.EnvVar[];
  additionalEnvVars?: k8s.types.input.core.v1.EnvVar[];
  additionalJvmOptions?: string;
  participantAddress: Output<string> | string;
  secrets: ValidatorSecrets | ValidatorSecretsConfig;
  sweep?: SweepConfig;
  autoAcceptTransfers?: AutoAcceptTransfersConfig;
  nodeIdentifier: string;
  dependencies: CnInput<pulumi.Resource>[];
  participantPruningConfig?: ParticipantPruningConfig;
  deduplicationDuration?: string;
  logLevel?: LogLevel;
  resources?: K8sResourceSchema;
};

export type ValidatorInstallConfig = BasicValidatorConfig & {
  svValidator: false;
  onboardingSecret: string;
  svSponsorAddress?: string;
  participantBootstrapDump?: BootstrappingDumpConfig;
  migration: {
    id: DomainMigrationIndex;
    migrating: boolean;
  };
};

export type AutoAcceptTransfersConfig = {
  fromParty: string;
  toParty: string;
};

export function autoAcceptTransfersConfigFromEnv(
  nodeName: string
): AutoAcceptTransfersConfig | undefined {
  const asJson = config.optionalEnv(`${nodeName}_AUTO_ACCEPT_TRANSFERS`);
  return asJson && JSON.parse(asJson);
}

type SvValidatorConfig = BasicValidatorConfig & {
  svValidator: true;
  decentralizedSynchronizerUrl?: string;
  migration: {
    id: DomainMigrationIndex;
  };
};

export async function installValidatorApp(
  baseConfig: ValidatorInstallConfig | SvValidatorConfig
): Promise<pulumi.Resource> {
  const backupConfig = baseConfig.backupConfig
    ? {
        ...baseConfig.backupConfig,
        config: {
          ...baseConfig.backupConfig.config,
          location: {
            ...baseConfig.backupConfig.config.location,
            prefix:
              baseConfig.backupConfig.config.location.prefix ||
              `${CLUSTER_BASENAME}/${baseConfig.xns.logicalName}`,
          },
        },
      }
    : undefined;

  const config = { ...baseConfig, backupConfig };

  const validatorSecrets =
    'validatorSecret' in config.secrets
      ? config.secrets
      : await installValidatorSecrets(config.secrets);

  const participantBootstrapDumpSecret: pulumi.Resource | undefined =
    !config.svValidator && config.participantBootstrapDump
      ? await fetchAndInstallParticipantBootstrapDump(config.xns, config.participantBootstrapDump)
      : undefined;

  const backupConfigSecret: pulumi.Resource | undefined = config.backupConfig
    ? config.backupConfig.secret
      ? config.backupConfig.secret
      : installBootstrapDataBucketSecret(config.xns, config.backupConfig.config.location.bucket)
    : undefined;

  const validatorOnboardingSecret =
    !config.svValidator && config.onboardingSecret
      ? [installValidatorOnboardingSecret(config.xns, 'validator', config.onboardingSecret)]
      : [];
  const dependsOn: CnInput<pulumi.Resource>[] = config.dependencies
    .concat([config.xns.ns])
    .concat(validatorOnboardingSecret)
    .concat(backupConfigSecret ? [backupConfigSecret] : [])
    .concat(participantBootstrapDumpSecret ? [participantBootstrapDumpSecret] : [])
    .concat([validatorSecrets.validatorSecret, validatorSecrets.wallet, validatorSecrets.cns])
    .concat(config.extraDependsOn || []);

  const walletSweep = config.sweep && {
    [config.sweep.fromParty]: {
      maxBalanceUSD: config.sweep.maxBalance,
      minBalanceUSD: config.sweep.minBalance,
      receiver: config.sweep.toParty,
    },
  };

  const autoAcceptTransfers = config.autoAcceptTransfers && {
    [config.autoAcceptTransfers.toParty]: {
      fromParties: [config.autoAcceptTransfers.fromParty],
    },
  };

  const chartVersion = activeVersion;

  return installSpliceHelmChart(
    config.xns,
    `validator-${config.xns.logicalName}`,
    'splice-validator',
    {
      migration: config.migration,
      additionalUsers: config.additionalUsers || [],
      additionalEnvVars: config.additionalEnvVars || undefined,
      validatorPartyHint: config.validatorPartyHint,
      appDars: config.appDars || [],
      decentralizedSynchronizerUrl: config.svValidator
        ? config.decentralizedSynchronizerUrl
        : undefined,
      scanAddress: config.scanAddress,
      extraDomains: config.extraDomains,
      validatorWalletUsers: config.validatorWalletUsers,
      svSponsorAddress: !config.svValidator ? config.svSponsorAddress : undefined,
      onboardingSecretFrom:
        !config.svValidator && config.onboardingSecret
          ? {
              secretKeyRef: {
                name: validatorOnboardingSecretName('validator'),
                key: 'secret',
                optional: false,
              },
            }
          : undefined,
      topup: config.topupConfig ? { enabled: true, ...config.topupConfig } : { enabled: false },
      persistence: config.persistenceConfig,
      disableAllocateLedgerApiUserParty: config.disableAllocateLedgerApiUserParty,
      participantIdentitiesDumpPeriodicBackup: config.backupConfig?.config,
      additionalConfig: config.additionalConfig,
      participantIdentitiesDumpImport:
        !config.svValidator && config.participantBootstrapDump
          ? { secretName: participantBootstrapDumpSecretName }
          : undefined,
      svValidator: config.svValidator,
      useSequencerConnectionsFromScan:
        !config.svValidator || config.decentralizedSynchronizerUrl === undefined,
      metrics: {
        enable: true,
      },
      participantAddress: config.participantAddress,
      additionalJvmOptions: getAdditionalJvmOptions(config.additionalJvmOptions),
      failOnAppVersionMismatch: failOnAppVersionMismatch,
      enablePostgresMetrics: true,
      auth: {
        audience:
          config.secrets.auth0Client.getCfg().appToApiAudience['validator'] || DEFAULT_AUDIENCE,
        jwksUrl: `https://${config.secrets.auth0Client.getCfg().auth0Domain}/.well-known/jwks.json`,
      },
      walletSweep,
      autoAcceptTransfers,
      contactPoint: daContactPoint,
      nodeIdentifier: config.nodeIdentifier,
      participantPruningSchedule: config.participantPruningConfig,
      deduplicationDuration: config.deduplicationDuration,
      maxVettingDelay: networkWideConfig?.maxVettingDelay,
      logLevel: config.logLevel,
      resources: baseConfig.svValidator ? config.resources : {},
      ...spliceInstanceNames,
    },
    chartVersion,
    { dependsOn }
  );
}

type ValidatorSecretsConfig = {
  xns: ExactNamespace;
  auth0Client: Auth0Client;
  auth0AppName: string;
};

export async function installValidatorSecrets(
  config: ValidatorSecretsConfig
): Promise<ValidatorSecrets> {
  return {
    validatorSecret: await installAuth0Secret(
      config.auth0Client,
      config.xns,
      'validator',
      config.auth0AppName
    ),
    wallet: await installAuth0UISecret(config.auth0Client, config.xns, 'wallet', 'wallet'),
    cns: await installAuth0UISecret(config.auth0Client, config.xns, 'cns', 'cns'),
    auth0Client: config.auth0Client,
  };
}
