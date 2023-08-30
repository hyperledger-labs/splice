import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import { installAuth0Secret, installAuth0UISecret, installCNHelmChart } from 'cn-pulumi-common';
import {
  Auth0Client,
  BackupConfig,
  BootstrappingDumpConfig,
  CLUSTER_BASENAME,
  ExactNamespace,
  fetchAndInstallParticipantBootstrapDump,
  installGcpBucketSecret,
  participantBootstrapDumpSecretName,
} from 'cn-pulumi-common';
import { domainFeesConfig } from 'cn-pulumi-common/src/domainFeesCfg';

import {
  ValidatorOnboarding,
  installValidatorOnboardingSecret,
  validatorOnboardingSecretName,
} from './sv';

export type ExtraDomain = {
  alias: string;
  url: string;
};

export type ValidatorBackupConfig = {
  // If not set the secret will be created.
  secret?: pulumi.Resource;
  config: BackupConfig;
};

export type ValidatorConfig = {
  auth0Client: Auth0Client;
  xns: ExactNamespace;
  auth0AppName: string;
  onboarding?: ValidatorOnboarding;
  withDomainFees: boolean;
  validatorWalletUser?: string;
  disableAllocateLedgerApiUserParty?: boolean;
  participant: pulumi.Resource;
  backupConfig?: ValidatorBackupConfig;
  extraDependsOn?: pulumi.Resource[];
  appDars?: string[];
  validatorPartyHint?: string;
  extraDomains?: ExtraDomain[];
  disableAdminAuth?: boolean;
  svSponsorAddress?: string;
  additionalConfig?: string;
  additionalUsers?: k8s.types.input.core.v1.EnvVar[];
  participantBootstrapDump?: BootstrappingDumpConfig;
};

export function installValidatorApp(config: ValidatorConfig): pulumi.Resource {
  const participantBootstrapDumpSecret: pulumi.Resource | undefined =
    config.participantBootstrapDump
      ? fetchAndInstallParticipantBootstrapDump(config.xns, config.participantBootstrapDump)
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
      : installGcpBucketSecret(config.xns, config.backupConfig.config.bucket)
    : undefined;

  const dependsOn: pulumi.Resource[] = [
    config.xns.ns,
    config.participant,
    installAuth0Secret(config.auth0Client, config.xns, 'validator', config.auth0AppName),
    installAuth0UISecret(config.auth0Client, config.xns, 'wallet', 'wallet'),
    installAuth0UISecret(config.auth0Client, config.xns, 'directory', 'directory'),
  ]
    .concat(
      config.onboarding ? [installValidatorOnboardingSecret(config.xns, config.onboarding)] : []
    )
    .concat(backupConfigSecret ? [backupConfigSecret] : [])
    .concat(participantBootstrapDumpSecret ? [participantBootstrapDumpSecret] : [])
    .concat(config.extraDependsOn || []);

  return installCNHelmChart(
    config.xns,
    'validator-' + config.xns.logicalName,
    'cn-validator',
    {
      additionalUsers: config.additionalUsers || [],
      validatorPartyHint: config.validatorPartyHint,
      appDars: config.appDars || [],
      globalDomainUrl: 'http://global-domain-sequencer.sv-1:5008',
      extraDomains: config.extraDomains,
      validatorWalletUser: config.validatorWalletUser,
      svSponsorAddress: config.svSponsorAddress,
      onboardingSecretFrom: config.onboarding
        ? {
            secretKeyRef: {
              name: validatorOnboardingSecretName(config.onboarding),
              key: 'secret',
              optional: false,
            },
          }
        : undefined,
      topup: config.withDomainFees
        ? {
            enabled: true,
            targetThroughput: domainFeesConfig.targetThroughput,
            minTopupInterval: domainFeesConfig.minTopupInterval,
          }
        : {},
      disableAdminAuth: config.disableAdminAuth,
      disableAllocateLedgerApiUserParty: config.disableAllocateLedgerApiUserParty,
      participantIdentitiesBackup: backupConfig,
      additionalConfig: config.additionalConfig,
      participantBootstrappingDump: config.participantBootstrapDump
        ? { secretName: participantBootstrapDumpSecretName }
        : undefined,
    },
    dependsOn
  );
}
