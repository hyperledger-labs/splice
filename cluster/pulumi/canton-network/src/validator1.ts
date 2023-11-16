import * as pulumi from '@pulumi/pulumi';
import {
  auth0UserNameEnvVarSource,
  installAuth0UISecret,
  exactNamespace,
  installCNHelmChart,
  BackupConfig,
  BootstrappingDumpConfig,
  ValidatorTopupConfig,
  CLUSTER_BASENAME,
  ValidatorOnboarding,
} from 'cn-pulumi-common';
import type { Auth0Client } from 'cn-pulumi-common';
import { jmxOptions } from 'cn-pulumi-common/src/jmx';

import * as postgres from './postgres';
import { installParticipant } from './ledger';
import { installValidatorApp } from './validator';

export async function installValidator1(
  auth0Client: Auth0Client,
  svc: pulumi.Resource,
  name: string,
  onboarding: ValidatorOnboarding,
  isDevNet: boolean,
  validatorWalletUser: string,
  backupConfig?: BackupConfig,
  participantBootstrapDump?: BootstrappingDumpConfig,
  topupConfig?: ValidatorTopupConfig
): Promise<pulumi.Resource> {
  const xns = exactNamespace(name, true);

  const postgresDb = postgres.installPostgres(xns, 'postgres');

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
    isDevNet,
    [loopback]
  );

  installCNHelmChart(xns, 'splitwell-web-ui', 'cn-splitwell-web-ui', {}, [
    await installAuth0UISecret(auth0Client, xns, 'splitwell', 'splitwell'),
  ]);

  const validatorDbName = 'validator1';
  const validatorDb = postgresDb.createDatabase(validatorDbName);

  const extraDependsOn: pulumi.Resource[] = [svc, postgresDb, validatorDb];

  return installValidatorApp({
    auth0Client,
    validatorWalletUser,
    xns,
    participant,
    appDars: ['cn-node-0.1.0-SNAPSHOT/dars/splitwell-0.1.0.dar'],
    validatorPartyHint: `${name}_validator_service_user`,
    extraDomains: [{ alias: 'splitwell', url: 'http://domain.splitwell:5008' }],
    svSponsorAddress: 'http://sv-app.sv-1:5014',
    onboarding,
    persistenceConfig: {
      host: postgresDb.address,
      password: postgresDb.password,
      databaseName: pulumi.Output.create(validatorDbName),
      schema: pulumi.Output.create(validatorDbName),
      user: pulumi.Output.create('cnadmin'),
      port: pulumi.Output.create(5432),
    },
    backupConfig: backupConfig ? { config: backupConfig } : undefined,
    extraDependsOn,
    auth0AppName: 'validator1',
    participantBootstrapDump,
    topupConfig,
    svValidator: false,
    additionalJvmOptions: jmxOptions(),
  });
}
