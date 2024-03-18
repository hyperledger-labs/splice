import * as pulumi from '@pulumi/pulumi';
import {
  Auth0Client,
  auth0UserNameEnvVar,
  BackupConfig,
  BootstrappingDumpConfig,
  CLUSTER_BASENAME,
  exactNamespace,
  ExactNamespace,
  GlobalDomainMigrationConfig,
  installAuth0Secret,
  installCNHelmChart,
  ValidatorTopupConfig,
} from 'cn-pulumi-common';
import { failOnAppVersionMismatch } from 'cn-pulumi-common/src/upgrades';

import * as postgres from './postgres';
import { installMigrationSpecificValidatorParticipant } from './participant';
import { installPostgresMetrics } from './postgres';
import { installValidatorApp } from './validator';

export async function installSplitwell(
  auth0Client: Auth0Client,
  providerWalletUser: string,
  validatorWalletUser: string,
  onboardingSecret: string,
  splitPostgresInstances: boolean,
  globalDomainMigrationConfig: GlobalDomainMigrationConfig,
  dependsOn: pulumi.Resource[],
  backupConfig?: BackupConfig,
  participantBootstrapDump?: BootstrappingDumpConfig,
  topupConfig?: ValidatorTopupConfig
): Promise<pulumi.Resource> {
  const xns = exactNamespace('splitwell', true);

  const sharedPostgres = splitPostgresInstances
    ? undefined
    : postgres.installPostgres(xns, 'splitwell-pg', splitPostgresInstances);

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

  installIngress(xns);

  const participant = installMigrationSpecificValidatorParticipant(
    globalDomainMigrationConfig,
    xns,
    sharedPostgres,
    participantBootstrapDump,
    'splitwell',
    [loopback]
  );

  const swPostgres = sharedPostgres || postgres.installPostgres(xns, 'sw-pg', true);
  const splitwellDbName = 'app_splitwell';

  const scanAddress = `http://scan-app.sv-1:5012`;
  installCNHelmChart(
    xns,
    'splitwell-app',
    'cn-splitwell-app',
    {
      postgres: swPostgres.address,
      metrics: {
        enable: true,
      },
      migration: {
        id: globalDomainMigrationConfig.activeMigrationId,
      },
      scanAddress: scanAddress,
      participantHost: participant.name,
      persistence: {
        host: swPostgres.address,
        databaseName: pulumi.Output.create(splitwellDbName),
        secretName: swPostgres.secretName,
        schema: pulumi.Output.create(splitwellDbName),
        user: pulumi.Output.create('cnadmin'),
        port: pulumi.Output.create(5432),
      },
      failOnAppVersionMismatch: failOnAppVersionMismatch(),
    },
    { dependsOn: dependsOn.concat([participant]) }
  );

  const validatorPostgres = sharedPostgres || postgres.installPostgres(xns, 'validator-pg', true);
  const validatorDbName = 'val_splitwell';

  const extraDependsOn = dependsOn.concat([
    await installAuth0Secret(auth0Client, xns, 'splitwell', 'splitwell'),
  ]);

  const validator = await installValidatorApp({
    xns,
    extraDependsOn,
    participant,
    ...globalDomainMigrationConfig.migratingNodeConfig(),
    additionalUsers: [
      auth0UserNameEnvVar('splitwell'),
      { name: 'CN_APP_SPLITWELL_PROVIDER_WALLET_USER_NAME', value: providerWalletUser },
    ],
    additionalConfig: [
      'canton.validator-apps.validator_backend.app-instances.splitwell = {',
      '  service-user = ${?CN_APP_SPLITWELL_LEDGER_API_AUTH_USER_NAME}',
      '  wallet-user = ${?CN_APP_SPLITWELL_PROVIDER_WALLET_USER_NAME}',
      // We vet both versions to easily test upgrades.
      '  dars = ["cn-node-0.1.0-SNAPSHOT/dars/splitwell-0.1.0.dar", "cn-node-0.1.0-SNAPSHOT/dars/splitwell-0.2.0.dar"]',
      '}',
    ].join('\n'),
    onboardingSecret,
    backupConfig: backupConfig ? { config: backupConfig } : undefined,
    svSponsorAddress: `http://sv-app.sv-1:5014`,
    participantBootstrapDump,
    participantAddress: participant.name,
    topupConfig: topupConfig,
    svValidator: false,
    persistenceConfig: {
      host: validatorPostgres.address,
      databaseName: pulumi.Output.create(validatorDbName),
      secretName: validatorPostgres.secretName,
      schema: pulumi.Output.create(validatorDbName),
      user: pulumi.Output.create('cnadmin'),
      port: pulumi.Output.create(5432),
    },
    scanAddress: scanAddress,
    secrets: {
      xns: xns,
      auth0Client: auth0Client,
      auth0AppName: 'splitwell_validator',
    },
    validatorWalletUser,
  });

  installPostgresMetrics(validatorPostgres, validatorDbName, [validator]);

  return validator;
}

function installIngress(xns: ExactNamespace) {
  installCNHelmChart(
    xns,
    'cluster-ingress-splitwell-uis',
    'cn-cluster-ingress-runbook',
    {
      cluster: {
        hostname: `${CLUSTER_BASENAME}.network.canton.global`,
        hostPrefix: '',
        svNamespace: xns.logicalName,
      },
      withSvIngress: false,
    },
    {}
  );
}
