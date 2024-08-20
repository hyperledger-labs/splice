import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import { Secret } from '@pulumi/kubernetes/core/v1';
import { Output } from '@pulumi/pulumi';
import {
  Auth0Client,
  BackupConfig,
  BootstrappingDumpConfig,
  CLUSTER_BASENAME,
  CnInput,
  config,
  daContactPoint,
  defaultVersion,
  DomainMigrationIndex,
  ExactNamespace,
  fetchAndInstallParticipantBootstrapDump,
  installAuth0Secret,
  installAuth0UISecret,
  installBootstrapDataBucketSecret,
  installCNHelmChart,
  installValidatorOnboardingSecret,
  participantBootstrapDumpSecretName,
  spliceInstanceNames,
  validatorOnboardingSecretName,
  ValidatorTopupConfig,
} from 'cn-pulumi-common';
import { CnChartVersion } from 'cn-pulumi-common/src/artifacts';
import { jmxOptions } from 'cn-pulumi-common/src/jmx';
import { failOnAppVersionMismatch } from 'cn-pulumi-common/src/upgrades';

import { PersistenceConfig } from '../../common';

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
  wallet: Secret;
  cns: Secret;
  auth0Client: Auth0Client;
};

type BasicValidatorConfig = {
  xns: ExactNamespace;
  topupConfig?: ValidatorTopupConfig;
  validatorWalletUser?: string;
  disableAllocateLedgerApiUserParty?: boolean;
  participant: pulumi.Resource;
  backupConfig?: ValidatorBackupConfig;
  extraDependsOn?: pulumi.Resource[];
  scanAddress: Output<string> | string;
  persistenceConfig: PersistenceConfig;
  appDars?: ((version: CnChartVersion) => string)[];
  validatorPartyHint?: string;
  extraDomains?: ExtraDomain[];
  additionalConfig?: (version: CnChartVersion) => string;
  additionalUsers?: k8s.types.input.core.v1.EnvVar[];
  participantAddress: Output<string> | string;
  secrets: ValidatorSecrets | ValidatorSecretsConfig;
  sweep?: SweepConfig;
  autoAcceptTransfers?: AutoAcceptTransfersConfig;
  nodeIdentifier: string;
};

export type ValidatorConfig = BasicValidatorConfig & {
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

export type SweepConfig = {
  fromParty: string;
  toParty: string;
  maxBalance: number;
  minBalance: number;
};

export function sweepConfigFromEnv(nodeName: string): SweepConfig | undefined {
  const asJson = config.optionalEnv(`${nodeName}_SWEEP`);
  return asJson && JSON.parse(asJson);
  // const fromParty = config.optionalEnv(`${nodeName}_SWEEP_FROM`);
  // const toParty = config.optionalEnv(`${nodeName}_SWEEP_TO`);
  // const maxBalance = config.optionalEnv(`${nodeName}_SWEEP_MAX_BALANCE`);
  // const minBalance = config.optionalEnv(`${nodeName}_SWEEP_MIN_BALANCE`);
  // if (fromParty && toParty && maxBalance && minBalance) {
  //   return {
  //     fromParty,
  //     toParty,
  //     maxBalance: parseInt(maxBalance),
  //     minBalance: parseInt(minBalance),
  //   };
  // }
  // if (fromParty || toParty || maxBalance || minBalance) {
  //   throw new Error(`All sweep config values must be set for ${nodeName}`);
  // }
  // return undefined;
}

type SvValidatorConfig = BasicValidatorConfig & {
  svValidator: true;
  decentralizedSynchronizerUrl: string;
  migration: {
    id: DomainMigrationIndex;
  };
};

export async function installValidatorApp(
  baseConfig: ValidatorConfig | SvValidatorConfig
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
  const dependsOn: CnInput<pulumi.Resource>[] = [config.xns.ns, config.participant]
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

  const chartVersion = defaultVersion;

  return installCNHelmChart(
    config.xns,
    `validator-${config.xns.logicalName}`,
    'cn-validator',
    {
      migration: config.migration,
      additionalUsers: config.additionalUsers || [],
      validatorPartyHint: config.validatorPartyHint,
      appDars: config.appDars ? config.appDars.map(f => f(chartVersion)) : [],
      decentralizedSynchronizerUrl: config.svValidator
        ? config.decentralizedSynchronizerUrl
        : undefined,
      scanAddress: config.scanAddress,
      extraDomains: config.extraDomains,
      validatorWalletUser: config.validatorWalletUser,
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
      additionalConfig: config.additionalConfig ? config.additionalConfig(chartVersion) : undefined,
      participantIdentitiesDumpImport:
        !config.svValidator && config.participantBootstrapDump
          ? { secretName: participantBootstrapDumpSecretName }
          : undefined,
      svValidator: config.svValidator,
      useSequencerConnectionsFromScan: !config.svValidator,
      metrics: {
        enable: true,
      },
      participantAddress: config.participantAddress,
      additionalJvmOptions: jmxOptions(),
      failOnAppVersionMismatch: failOnAppVersionMismatch(),
      enablePostgresMetrics: true,
      auth: {
        audience: config.secrets.auth0Client.getCfg().appToApiAudience['validator'],
        ledgerApiAudience: config.secrets.auth0Client.getCfg().appToApiAudience['participant'],
        jwksUrl: `https://${config.secrets.auth0Client.getCfg().auth0Domain}/.well-known/jwks.json`,
      },
      walletSweep,
      autoAcceptTransfers,
      contactPoint: daContactPoint,
      nodeIdentifier: config.nodeIdentifier,
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
