import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
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

export type ValidatorConfig = {
  auth0Client: Auth0Client;
  xns: ExactNamespace;
  auth0AppName: string;
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
  additionalJvmOptions?: string;
};

export async function installValidatorApp(config: ValidatorConfig): Promise<pulumi.Resource> {
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

  const dependsOn: CnInput<pulumi.Resource>[] = [
    config.xns.ns,
    config.participant,
    await installAuth0Secret(config.auth0Client, config.xns, 'validator', config.auth0AppName),
    await installAuth0UISecret(config.auth0Client, config.xns, 'wallet', 'wallet'),
    await installAuth0UISecret(config.auth0Client, config.xns, 'cns', 'cns'),
  ]
    .concat(
      config.onboardingSecret
        ? [installValidatorOnboardingSecret(config.xns, 'validator', config.onboardingSecret)]
        : []
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
      globalDomainUrl: `https://sequencer.sv-1.svc.${CLUSTER_BASENAME}.network.canton.global`,
      extraDomains: config.extraDomains,
      validatorWalletUser: config.validatorWalletUser,
      svSponsorAddress: config.svSponsorAddress,
      onboardingSecretFrom: config.onboardingSecret
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
      postgresSecretName: config.persistenceConfig.secretName,
    },
    dependsOn
  );
}
