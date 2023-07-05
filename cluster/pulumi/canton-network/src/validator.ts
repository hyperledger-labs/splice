import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import { installAuth0Secret, installAuth0UISecret, installCNHelmChart } from 'cn-pulumi-common';
import type { Auth0Client, ExactNamespace } from 'cn-pulumi-common';

import {
  BackupConfig,
  ParticipantBootstrapDumpConfig,
  fetchAndInstallParticipantBootstrapDump,
  installGcpBucketSecret,
  participantBootstrapDumpSecretName,
} from './backup';
import { domainFeesConfig } from './domainFeesCfg';
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
  participantBootstrapDump?: ParticipantBootstrapDumpConfig;
};

export async function installValidatorApp(config: ValidatorConfig): Promise<pulumi.Resource> {
  const participantBootstrapDumpSecret: pulumi.Resource | undefined =
    config.participantBootstrapDump
      ? fetchAndInstallParticipantBootstrapDump(config.xns, config.participantBootstrapDump)
      : undefined;

  const dependsOn: pulumi.Resource[] = [
    config.xns.ns,
    config.participant,
    await installAuth0Secret(config.auth0Client, config.xns, 'validator', config.auth0AppName),
    await installAuth0UISecret(config.auth0Client, config.xns, 'wallet', 'wallet'),
    await installAuth0UISecret(config.auth0Client, config.xns, 'directory', 'directory'),
  ]
    .concat(
      config.onboarding ? [installValidatorOnboardingSecret(config.xns, config.onboarding)] : []
    )
    .concat(
      config.backupConfig
        ? config.backupConfig.secret
          ? [config.backupConfig.secret]
          : [installGcpBucketSecret(config.xns, config.backupConfig.config.bucket)]
        : []
    )
    .concat(config.extraDependsOn || [])
    .concat(participantBootstrapDumpSecret ? [participantBootstrapDumpSecret] : []);

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
      foundingSvApiUrl: 'http://sv-app.sv-1:5014',
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
      participantIdentitiesBackup: config.backupConfig?.config,
      additionalConfig: config.additionalConfig,
      participantBootstrappingDump: config.participantBootstrapDump
        ? { secretName: participantBootstrapDumpSecretName }
        : undefined,
    },
    dependsOn
  );
}
