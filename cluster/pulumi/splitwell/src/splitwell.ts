import * as pulumi from '@pulumi/pulumi';
import * as postgres from 'splice-pulumi-common/src/postgres';
import {
  Auth0Client,
  auth0UserNameEnvVar,
  BackupConfig,
  BootstrappingDumpConfig,
  CLUSTER_HOSTNAME,
  exactNamespace,
  ExactNamespace,
  DecentralizedSynchronizerMigrationConfig,
  installAuth0Secret,
  installSpliceHelmChart,
  ValidatorTopupConfig,
  config,
  splitwellDarPath,
  imagePullSecret,
  CnInput,
  activeVersion,
} from 'splice-pulumi-common';
import { installMigrationSpecificValidatorParticipant } from 'splice-pulumi-common-validator/src/migrationSpecificParticipant';
import { installValidatorApp } from 'splice-pulumi-common-validator/src/validator';
import { failOnAppVersionMismatch } from 'splice-pulumi-common/src/upgrades';

export async function installSplitwell(
  auth0Client: Auth0Client,
  providerWalletUser: string,
  validatorWalletUser: string,
  onboardingSecret: string,
  splitPostgresInstances: boolean,
  decentralizedSynchronizerMigrationConfig: DecentralizedSynchronizerMigrationConfig,
  backupConfig?: BackupConfig,
  participantBootstrapDump?: BootstrappingDumpConfig,
  topupConfig?: ValidatorTopupConfig
): Promise<pulumi.Resource> {
  const xns = exactNamespace('splitwell', true);
  const sharedPostgres = splitPostgresInstances
    ? undefined
    : postgres.installPostgres(xns, 'splitwell-pg', 'splitwell-pg', splitPostgresInstances);

  const loopback = installSpliceHelmChart(
    xns,
    'loopback',
    'cn-cluster-loopback-gateway',
    {
      cluster: {
        hostname: CLUSTER_HOSTNAME,
      },
    },
    activeVersion,
    { dependsOn: [xns.ns] }
  );

  const imagePullDeps = activeVersion.type === 'local' ? [] : imagePullSecret(xns);

  installIngress(xns, imagePullDeps);

  const participant = installMigrationSpecificValidatorParticipant(
    decentralizedSynchronizerMigrationConfig,
    xns,
    sharedPostgres,
    'splitwell',
    auth0Client.getCfg(),
    undefined,
    imagePullDeps.concat([loopback])
  );

  const swPostgres = sharedPostgres || postgres.installPostgres(xns, 'sw-pg', 'sw-pg', true);
  const splitwellDbName = 'app_splitwell';

  const scanAddress = `http://scan-app.sv-1:5012`;
  installSpliceHelmChart(
    xns,
    'splitwell-app',
    'cn-splitwell-app',
    {
      postgres: swPostgres.address,
      metrics: {
        enable: true,
      },
      migration: {
        id: decentralizedSynchronizerMigrationConfig.active.id,
      },
      scanAddress: scanAddress,
      participantHost: participant.participantAddress,
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
    activeVersion,
    { dependsOn: imagePullDeps }
  );

  const validatorPostgres =
    sharedPostgres || postgres.installPostgres(xns, 'validator-pg', 'validator-pg', true);
  const validatorDbName = 'val_splitwell';

  const extraDependsOn = imagePullDeps
    .concat(await installAuth0Secret(auth0Client, xns, 'splitwell', 'splitwell', 'splice'))
    .concat(await installAuth0Secret(auth0Client, xns, 'splitwell', 'splitwell', 'cn'));

  const validator = await installValidatorApp({
    xns,
    extraDependsOn,
    dependencies: [],
    ...decentralizedSynchronizerMigrationConfig.migratingNodeConfig(),
    additionalUsers: [
      auth0UserNameEnvVar('splitwell'),
      { name: 'CN_APP_SPLITWELL_PROVIDER_WALLET_USER_NAME', value: providerWalletUser },
    ],
    additionalConfig: [
      'canton.validator-apps.validator_backend.app-instances.splitwell = {',
      '  service-user = ${?CN_APP_SPLITWELL_LEDGER_API_AUTH_USER_NAME}',
      '  wallet-user = ${?CN_APP_SPLITWELL_PROVIDER_WALLET_USER_NAME}',
      // We vet both versions to easily test upgrades.
      `  dars = ["${splitwellDarPath}"]`,
      '}',
    ].join('\n'),
    onboardingSecret,
    backupConfig: backupConfig ? { config: backupConfig } : undefined,
    svSponsorAddress: `http://sv-app.sv-1:5014`,
    participantBootstrapDump,
    participantAddress: participant.participantAddress,
    topupConfig: topupConfig,
    svValidator: false,
    persistenceConfig: {
      host: validatorPostgres.address,
      databaseName: pulumi.Output.create(validatorDbName),
      secretName: validatorPostgres.secretName,
      schema: pulumi.Output.create(validatorDbName),
      user: pulumi.Output.create('cnadmin'),
      port: pulumi.Output.create(5432),
      postgresName: validatorPostgres.instanceName,
    },
    scanAddress: scanAddress,
    secrets: {
      xns: xns,
      auth0Client: auth0Client,
      auth0AppName: 'splitwell_validator',
    },
    validatorWalletUser,
    // TODO(#14199) Remove this with the next reset
    validatorPartyHint: config.envFlag('VALIDATOR_LEGACY_PARTY_HINT')
      ? config.requireEnv('CN_SPLITWELL_VALIDATOR_LEGACY_PARTY_HINT')
      : 'digitalasset-splitwell-1',
    nodeIdentifier: 'splitwell',
  });

  return validator;
}

function installIngress(xns: ExactNamespace, dependsOn: CnInput<pulumi.Resource>[]) {
  installSpliceHelmChart(xns, 'cluster-ingress-splitwell-uis', 'cn-cluster-ingress-runbook', {
    cluster: {
      hostname: CLUSTER_HOSTNAME,
      svNamespace: xns.logicalName,
    },
    withSvIngress: false,
    opts: {
      dependsOn: dependsOn,
    },
  });
}
