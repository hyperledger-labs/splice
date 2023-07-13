import * as pulumi from '@pulumi/pulumi';
import {
  auth0UserNameEnvVarSource,
  installAuth0UISecret,
  exactNamespace,
  installCNHelmChart,
  BackupConfig,
  BootstrappingDumpConfig,
} from 'cn-pulumi-common';
import type { Auth0Client } from 'cn-pulumi-common';

import * as postgres from './postgres';
import { installParticipant } from './ledger';
import { ValidatorOnboarding } from './sv';
import { installValidatorApp } from './validator';

export async function installValidator1(
  auth0Client: Auth0Client,
  svc: pulumi.Resource,
  name: string,
  onboarding: ValidatorOnboarding,
  withDomainFees = false,
  isDevNet: boolean,
  validatorWalletUser: string,
  backupConfig?: BackupConfig,
  participantBootstrapDump?: BootstrappingDumpConfig
): Promise<pulumi.Resource> {
  const xns = exactNamespace(name);

  const postgresDb = postgres.installPostgres(xns, 'postgres');

  const participant = installParticipant(
    xns,
    'participant',
    postgresDb,
    auth0UserNameEnvVarSource('validator'),
    // We disable auto-init if we have a dump to bootstrap from.
    !!participantBootstrapDump
  );

  installCNHelmChart(xns, 'splitwell-web-ui', 'cn-splitwell-web-ui', {}, [
    installAuth0UISecret(auth0Client, xns, 'splitwell', 'splitwell'),
  ]);

  const extraDependsOn: pulumi.Resource[] = [svc];

  return installValidatorApp({
    auth0Client,
    withDomainFees,
    validatorWalletUser,
    xns,
    participant,
    appDars: [
      'cn-node-0.1.0-SNAPSHOT/dars/directory-service-0.1.0.dar',
      'cn-node-0.1.0-SNAPSHOT/dars/splitwell-0.1.0.dar',
    ],
    validatorPartyHint: `${name}_validator_service_user`,
    extraDomains: [{ alias: 'splitwell', url: 'http://domain.splitwell:5008' }],
    svSponsorAddress: 'http://sv-app.sv-1:5014',
    onboarding,
    // TODO(#6247) Enable auth here once Validator1PreflightIntegrationTest
    // supports this.
    disableAdminAuth: isDevNet,
    backupConfig: backupConfig ? { config: backupConfig } : undefined,
    extraDependsOn,
    auth0AppName: 'validator1',
    participantBootstrapDump,
  });
}
