import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import { Secret } from '@pulumi/kubernetes/core/v1';
import { Output } from '@pulumi/pulumi';
import {
  CnInput,
  installAuth0Secret,
  installAuth0UISecret,
  installCNHelmChart,
} from 'cn-pulumi-common';
import {
  Auth0Client,
  BackupConfig,
  BootstrappingDumpConfig,
  ValidatorTopupConfig,
  CLUSTER_BASENAME,
  ExactNamespace,
  fetchAndInstallParticipantBootstrapDump,
  installBootstrapDataBucketSecret,
  participantBootstrapDumpSecretName,
  installValidatorOnboardingSecret,
  validatorOnboardingSecretName,
} from 'cn-pulumi-common';
import { jmxOptions } from 'cn-pulumi-common/src/jmx';

import { PersistenceConfig } from '../../common';
import { DomainMigrationIndex } from './globalDomainNode';

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
export type ValidatorConfig = {
  xns: ExactNamespace;
  onboardingSecret?: string;
  topupConfig?: ValidatorTopupConfig;
  validatorWalletUser?: string;
  disableAllocateLedgerApiUserParty?: boolean;
  participant: pulumi.Resource;
  persistenceConfig: PersistenceConfig;
  backupConfig?: ValidatorBackupConfig;
  extraDependsOn?: pulumi.Resource[];
  appDars?: string[];
  validatorPartyHint?: string;
  extraDomains?: ExtraDomain[];
  svSponsorAddress?: string;
  additionalConfig?: string;
  additionalUsers?: k8s.types.input.core.v1.EnvVar[];
  participantBootstrapDump?: BootstrappingDumpConfig;
  svValidator?: boolean;
  participantAddress: Output<string> | string;
  globalDomainUrl: string;
  scanAddress: Output<string> | string;
  domainMigrationId?: DomainMigrationIndex;
  secrets: ValidatorSecrets | ValidatorSecretsConfig;
};

export async function installValidatorApp(config: ValidatorConfig): Promise<pulumi.Resource> {
  function maybeDomainSuffixed(value: string) {
    if (config.domainMigrationId != undefined) {
      return `${value}-${config.domainMigrationId}`;
    } else {
      return value;
    }
  }

  const validatorSecrets =
    'validatorSecret' in config.secrets
      ? config.secrets
      : await installValidatorSecrets(config.secrets);

  const participantBootstrapDumpSecret: pulumi.Resource | undefined =
    config.participantBootstrapDump
      ? await fetchAndInstallParticipantBootstrapDump(config.xns, config.participantBootstrapDump)
      : undefined;

  const backupConfig: BackupConfig | undefined = config.backupConfig
    ? {
        ...config.backupConfig.config,
        prefix: config.backupConfig.config.prefix
          ? config.backupConfig.config.prefix
          : `${CLUSTER_BASENAME}/${config.xns.logicalName}`,
      }
    : undefined;

  const backupConfigSecret: pulumi.Resource | undefined = config.backupConfig
    ? config.backupConfig.secret
      ? config.backupConfig.secret
      : installBootstrapDataBucketSecret(config.xns, config.backupConfig.config.bucket)
    : undefined;

  const validatorOnboardingSecret = config.onboardingSecret
    ? [
        installValidatorOnboardingSecret(
          config.xns,
          maybeDomainSuffixed('validator'),
          config.onboardingSecret
        ),
      ]
    : [];
  const dependsOn: CnInput<pulumi.Resource>[] = [config.xns.ns, config.participant]
    .concat(validatorOnboardingSecret)
    .concat(backupConfigSecret ? [backupConfigSecret] : [])
    .concat(participantBootstrapDumpSecret ? [participantBootstrapDumpSecret] : [])
    .concat([validatorSecrets.validatorSecret, validatorSecrets.wallet, validatorSecrets.cns])
    .concat(config.extraDependsOn || []);

  return installCNHelmChart(
    config.xns,
    maybeDomainSuffixed('validator-' + config.xns.logicalName),
    'cn-validator',
    {
      domainMigrationId: config.domainMigrationId?.toString(),
      additionalUsers: config.additionalUsers || [],
      validatorPartyHint: config.validatorPartyHint,
      appDars: config.appDars || [],
      globalDomainUrl: config.globalDomainUrl,
      scanAddress: config.scanAddress,
      extraDomains: config.extraDomains,
      validatorWalletUser: config.validatorWalletUser,
      svSponsorAddress: config.svSponsorAddress,
      onboardingSecretFrom: config.onboardingSecret
        ? {
            secretKeyRef: {
              name: validatorOnboardingSecretName(maybeDomainSuffixed('validator')),
              key: 'secret',
              optional: false,
            },
          }
        : undefined,
      topup: config.topupConfig ? { enabled: true, ...config.topupConfig } : { enabled: false },
      persistence: config.persistenceConfig,
      disableAllocateLedgerApiUserParty: config.disableAllocateLedgerApiUserParty,
      participantIdentitiesDumpPeriodicBackup: backupConfig,
      additionalConfig: config.additionalConfig,
      participantIdentitiesDumpImport: config.participantBootstrapDump
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
