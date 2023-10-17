import * as pulumi from '@pulumi/pulumi';
import {
  auth0UserNameEnvVar,
  auth0UserNameEnvVarSource,
  installAuth0Secret,
  exactNamespace,
  installCNHelmChart,
  CLUSTER_BASENAME,
  ValidatorTopupConfig,
  ValidatorOnboarding,
} from 'cn-pulumi-common';
import type { Auth0Client, BackupConfig, BootstrappingDumpConfig } from 'cn-pulumi-common';

import * as postgres from './postgres';
import { installDomain, installParticipant } from './ledger';
import { installValidatorApp } from './validator';

export async function installSplitwell(
  auth0Client: Auth0Client,
  svc: pulumi.Resource,
  providerWalletUser: string,
  onboarding: ValidatorOnboarding,
  backupConfig?: BackupConfig,
  participantBootstrapDump?: BootstrappingDumpConfig,
  topupConfig?: ValidatorTopupConfig
): Promise<pulumi.Resource> {
  const xns = exactNamespace('splitwell', true);

  const postgresDb = postgres.installPostgres(xns, 'postgres');

  const domain = installDomain(xns, 'domain', postgresDb);

  const loopback = installCNHelmChart(
    xns,
    'loopback',
    'cn-cluster-loopback-gateway',
    {
      cluster: {
        basename: CLUSTER_BASENAME,
      },
    },
    [xns.ns]
  );

  const participant = installParticipant(
    xns,
    'participant',
    postgresDb,
    auth0UserNameEnvVarSource('validator'),
    // We disable auto-init if we have a dump to bootstrap from.
    !!participantBootstrapDump,
    [domain, loopback]
  );

  installCNHelmChart(
    xns,
    'splitwell-app',
    'cn-splitwell-app',
    {
      postgres: postgresDb.address,
      postgresPassword: postgresDb.password,
    },
    [svc, participant]
  );

  const extraDependsOn = [svc, installAuth0Secret(auth0Client, xns, 'splitwell', 'splitwell')];

  return installValidatorApp({
    auth0Client,
    xns,
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
    topupConfig: topupConfig,
    svValidator: false,
  });
}
