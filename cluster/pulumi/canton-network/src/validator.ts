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
  DomainMigrationIndex,
  ExactNamespace,
  fetchAndInstallParticipantBootstrapDump,
  installAuth0Secret,
  installAuth0UISecret,
  installBootstrapDataBucketSecret,
  installCNHelmChart,
  installValidatorOnboardingSecret,
  participantBootstrapDumpSecretName,
  validatorOnboardingSecretName,
  ValidatorTopupConfig,
} from 'cn-pulumi-common';
import { jmxOptions } from 'cn-pulumi-common/src/jmx';

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
  appDars?: string[];
  validatorPartyHint?: string;
  extraDomains?: ExtraDomain[];
  additionalConfig?: string;
  additionalUsers?: k8s.types.input.core.v1.EnvVar[];
  participantAddress: Output<string> | string;
  secrets: ValidatorSecrets | ValidatorSecretsConfig;
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

type SvValidatorConfig = BasicValidatorConfig & {
  svValidator: true;
  globalDomainUrl: string;
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

  return installCNHelmChart(
    config.xns,
    `validator-${config.xns.logicalName}`,
    'cn-validator',
    {
      migration: config.migration,
      additionalUsers: config.additionalUsers || [],
      validatorPartyHint: config.validatorPartyHint,
      appDars: config.appDars || [],
      globalDomainUrl: config.svValidator ? config.globalDomainUrl : undefined,
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
      additionalConfig: config.additionalConfig,
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
    },
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
  };
}
