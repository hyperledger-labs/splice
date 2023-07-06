import * as pulumi from '@pulumi/pulumi';
import {
  auth0UserNameEnvVar,
  auth0UserNameEnvVarSource,
  installAuth0Secret,
  exactNamespace,
  installCNHelmChart,
} from 'cn-pulumi-common';
import type { Auth0Client } from 'cn-pulumi-common';

import * as postgres from './postgres';
import { BackupConfig, BootstrappingDumpConfig } from './backup';
import { installDomain, installParticipant } from './ledger';
import { ValidatorOnboarding } from './sv';
import { installValidatorApp } from './validator';

export async function installSplitwell(
  auth0Client: Auth0Client,
  svc: pulumi.Resource,
  providerWalletUser: string,
  onboarding: ValidatorOnboarding,
  withDomainFees = false,
  postgresPassword: pulumi.Input<string>,
  backupConfig?: BackupConfig,
  participantBootstrapDump?: BootstrappingDumpConfig
): Promise<pulumi.Resource> {
  const xns = exactNamespace('splitwell');

  const postgresDb = postgres.installPostgres(xns, 'postgres', postgresPassword);

  const domain = installDomain(xns, 'domain', postgresDb, postgresPassword);

  const participant = installParticipant(
    xns,
    'participant',
    postgresDb,
    auth0UserNameEnvVarSource('validator'),
    postgresPassword,
    // We disable auto-init if we have a dump to bootstrap from.
    !!participantBootstrapDump,
    [domain]
  );

  installCNHelmChart(
    xns,
    'splitwell-app',
    'cn-splitwell-app',
    {
      postgres: postgresDb,
      postgresPassword,
    },
    [svc, participant]
  );

  const extraDependsOn = [
    svc,
    await installAuth0Secret(auth0Client, xns, 'splitwell', 'splitwell'),
  ];

  return await installValidatorApp({
    auth0Client,
    xns,
    withDomainFees,
    extraDependsOn,
    participant,
    additionalUsers: [
      auth0UserNameEnvVar('splitwell'),
      { name: 'CN_APP_SPLITWELL_PROVIDER_WALLET_USER_NAME', value: providerWalletUser },
    ],
    extraDomains: [{ alias: 'splitwell', url: 'http://domain.splitwell:5008' }],
    additionalConfig: [
      'canton.validator-apps.validator_backend.app-instances.splitwise = {',
      '  service-user = ${?CN_APP_SPLITWELL_LEDGER_API_AUTH_USER_NAME}',
      '  wallet-user = ${?CN_APP_SPLITWELL_PROVIDER_WALLET_USER_NAME}',
      '  dars = ["cn-node-0.1.0-SNAPSHOT/dars/splitwell-0.1.0.dar"]',
      '}',
    ].join('\n'),
    onboarding,
    backupConfig: backupConfig ? { config: backupConfig } : undefined,
    svSponsorAddress: 'http://sv-app.sv-1:5014',
    auth0AppName: 'splitwell_validator',
    participantBootstrapDump,
  });
}
