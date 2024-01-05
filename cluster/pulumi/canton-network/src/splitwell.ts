import * as pulumi from '@pulumi/pulumi';
import {
  auth0UserNameEnvVar,
  auth0UserNameEnvVarSource,
  installAuth0Secret,
  exactNamespace,
  installCNHelmChart,
  CLUSTER_BASENAME,
  ValidatorTopupConfig,
  ExactNamespace,
  sanitizedForPostgres,
} from 'cn-pulumi-common';
import type { Auth0Client, BackupConfig, BootstrappingDumpConfig } from 'cn-pulumi-common';
import { jmxOptions } from 'cn-pulumi-common/src/jmx';

import * as postgres from './postgres';
import { installParticipant } from './ledger';
import { initDatabase, Postgres } from './postgres';
import { installValidatorApp } from './validator';

export async function installSplitwell(
  auth0Client: Auth0Client,
  svc: pulumi.Resource,
  providerWalletUser: string,
  onboardingSecret: string,
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
    { dependsOn: [xns.ns] }
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
      metrics: {
        enable: true,
      },
    },
    { dependsOn: [svc, participant] }
  );

  const validatorDbName = 'val_splitwell';
  const validatorDb = postgresDb.createDatabaseAndInstallMetrics(validatorDbName);

  const extraDependsOn = [
    svc,
    await installAuth0Secret(auth0Client, xns, 'splitwell', 'splitwell'),
    validatorDb,
  ];

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
    onboardingSecret,
    backupConfig: backupConfig ? { config: backupConfig } : undefined,
    svSponsorAddress: 'http://sv-app.sv-1:5014',
    auth0AppName: 'splitwell_validator',
    participantBootstrapDump,
    participantAddress: 'participant',
    topupConfig: topupConfig,
    svValidator: false,
    persistenceConfig: {
      host: postgresDb.address,
      databaseName: pulumi.Output.create(validatorDbName),
      secretName: postgresDb.secretName,
      schema: pulumi.Output.create(validatorDbName),
      user: pulumi.Output.create('cnadmin'),
      port: pulumi.Output.create(5432),
    },
  });
}

function installDomain(xns: ExactNamespace, name: string, postgres: Postgres): pulumi.Resource {
  const sanitizedName = sanitizedForPostgres(name);

  const mediatorDbName = `${sanitizedName}_mediator`;
  const mediatorDb = postgres.createDatabaseAndInstallMetrics(mediatorDbName);

  const sequencerDbName = `${sanitizedName}_sequencer`;
  const sequencerDb = postgres.createDatabaseAndInstallMetrics(sequencerDbName);

  const initDb = initDatabase();

  return installCNHelmChart(
    xns,
    name,
    'cn-domain',
    {
      postgres: postgres.address,
      postgresMediatorDb: mediatorDbName,
      postgresSequencerDb: sequencerDbName,
      additionalJvmOptions: jmxOptions(),
      init: initDb && { initDb },
    },
    {
      dependsOn: [mediatorDb, sequencerDb],
    }
  );
}
