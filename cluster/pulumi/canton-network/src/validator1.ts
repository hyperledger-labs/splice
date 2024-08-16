import * as pulumi from '@pulumi/pulumi';
import {
  Auth0Client,
  BackupConfig,
  BootstrappingDumpConfig,
  CLUSTER_HOSTNAME,
  defaultVersion,
  ExactNamespace,
  exactNamespace,
  DecentralizedSynchronizerMigrationConfig,
  installAuth0UISecret,
  installCNHelmChart,
  ValidatorTopupConfig,
  spliceInstanceNames,
} from 'cn-pulumi-common';

import * as postgres from '../../common/src/postgres';
import { installMigrationSpecificValidatorParticipant } from './participant';
import {
  AutoAcceptTransfersConfig,
  installValidatorApp,
  installValidatorSecrets,
} from './validator';

export async function installValidator1(
  auth0Client: Auth0Client,
  name: string,
  onboardingSecret: string,
  validatorWalletUser: string,
  splitPostgresInstances: boolean,
  decentralizedSynchronizerMigrationConfig: DecentralizedSynchronizerMigrationConfig,
  installSplitwell: boolean,
  dependsOn: pulumi.Resource[],
  backupConfig?: BackupConfig,
  participantBootstrapDump?: BootstrappingDumpConfig,
  topupConfig?: ValidatorTopupConfig,
  autoAcceptTransfers?: AutoAcceptTransfersConfig
): Promise<pulumi.Resource> {
  const xns = exactNamespace(name, true);

  const loopback = installCNHelmChart(
    xns,
    'loopback',
    'cn-cluster-loopback-gateway',
    {
      cluster: {
        hostname: CLUSTER_HOSTNAME,
      },
    },
    defaultVersion,
    { dependsOn: [xns.ns] }
  );

  const defaultPostgres = !splitPostgresInstances
    ? postgres.installPostgres(xns, 'postgres', 'postgres', false)
    : undefined;

  const validatorPostgres =
    defaultPostgres || postgres.installPostgres(xns, `validator-pg`, `validator-pg`, true);
  const validatorDbName = `validator1`;

  const validatorSecrets = await installValidatorSecrets({
    xns,
    auth0Client,
    auth0AppName: 'validator1',
  });

  const participant = installMigrationSpecificValidatorParticipant(
    decentralizedSynchronizerMigrationConfig,
    xns,
    defaultPostgres,
    participantBootstrapDump,
    'validator1',
    auth0Client.getCfg(),
    undefined,
    dependsOn.concat([loopback])
  );

  const extraDependsOn: pulumi.Resource[] = dependsOn.concat([participant, validatorPostgres]);
  const scanAddress = `http://scan-app.sv-1:5012`;

  const validator = await installValidatorApp({
    validatorWalletUser,
    xns,
    participant,
    ...decentralizedSynchronizerMigrationConfig.migratingNodeConfig(),
    // We vet both versions to easily test upgrades.
    appDars: ['splice-node/dars/splitwell-current.dar'],
    validatorPartyHint: `digitalasset-${name}-1`,
    svSponsorAddress: `http://sv-app.sv-1:5014`,
    onboardingSecret,
    persistenceConfig: {
      host: validatorPostgres.address,
      databaseName: pulumi.Output.create(validatorDbName),
      secretName: validatorPostgres.secretName,
      schema: pulumi.Output.create(validatorDbName),
      user: pulumi.Output.create('cnadmin'),
      port: pulumi.Output.create(5432),
      postgresName: validatorPostgres.instanceName,
    },
    backupConfig: backupConfig ? { config: backupConfig } : undefined,
    extraDependsOn,
    participantBootstrapDump,
    participantAddress: participant.name,
    topupConfig,
    svValidator: false,
    scanAddress,
    secrets: validatorSecrets,
    autoAcceptTransfers: autoAcceptTransfers,
  });
  installIngress(xns, installSplitwell, decentralizedSynchronizerMigrationConfig);

  if (installSplitwell) {
    installCNHelmChart(
      xns,
      'splitwell-web-ui',
      'cn-splitwell-web-ui',
      {
        ...spliceInstanceNames,
      },
      defaultVersion,
      {
        dependsOn: [await installAuth0UISecret(auth0Client, xns, 'splitwell', 'splitwell')],
      }
    );
  }

  return validator;
}

function installIngress(
  xns: ExactNamespace,
  splitwell: boolean,
  decentralizedSynchronizerMigrationConfig: DecentralizedSynchronizerMigrationConfig
) {
  installCNHelmChart(xns, `cluster-ingress-${xns.logicalName}`, 'cn-cluster-ingress-runbook', {
    cluster: {
      hostname: CLUSTER_HOSTNAME,
      svNamespace: xns.logicalName,
    },
    withSvIngress: false,
    ingress: {
      splitwell: splitwell,
      decentralizedSynchronizer: {
        activeMigrationId: decentralizedSynchronizerMigrationConfig.active.migrationId.toString(),
      },
    },
  });
}
