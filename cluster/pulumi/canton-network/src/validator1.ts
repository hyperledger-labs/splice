import * as pulumi from '@pulumi/pulumi';
import {
  Auth0Client,
  BackupConfig,
  BootstrappingDumpConfig,
  CLUSTER_HOSTNAME,
  activeVersion,
  ExactNamespace,
  exactNamespace,
  DecentralizedSynchronizerMigrationConfig,
  installAuth0UISecret,
  installSpliceHelmChart,
  ValidatorTopupConfig,
  spliceInstanceNames,
  config,
  splitwellDarPath,
  imagePullSecret,
  CnInput,
} from 'splice-pulumi-common';

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

  const participantDependsOn: CnInput<pulumi.Resource>[] = imagePullDeps
    .concat(dependsOn)
    .concat([loopback]);

  const participant = installMigrationSpecificValidatorParticipant(
    decentralizedSynchronizerMigrationConfig,
    xns,
    defaultPostgres,
    'validator1',
    auth0Client.getCfg(),
    undefined,
    participantDependsOn
  );

  const extraDependsOn: CnInput<pulumi.Resource>[] = participantDependsOn.concat([
    validatorPostgres,
  ]);
  const scanAddress = `http://scan-app.sv-1:5012`;

  const validator = await installValidatorApp({
    validatorWalletUser,
    xns,
    dependencies: [],
    ...decentralizedSynchronizerMigrationConfig.migratingNodeConfig(),
    appDars: [splitwellDarPath],
    // TODO(#14199) Remove this with the next reset
    validatorPartyHint: config.envFlag('VALIDATOR_LEGACY_PARTY_HINT')
      ? `${name}_validator_service_user`
      : `digitalasset-${name}-1`,
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
    participantAddress: participant.participantAddress,
    topupConfig,
    svValidator: false,
    scanAddress,
    secrets: validatorSecrets,
    autoAcceptTransfers: autoAcceptTransfers,
    nodeIdentifier: 'validator1',
  });
  installIngress(xns, installSplitwell, decentralizedSynchronizerMigrationConfig);

  if (installSplitwell) {
    installSpliceHelmChart(
      xns,
      'splitwell-web-ui',
      'cn-splitwell-web-ui',
      {
        ...spliceInstanceNames,
        auth: {
          audience: 'https://canton.network.global',
        },
        clusterUrl: CLUSTER_HOSTNAME,
      },
      activeVersion,
      {
        dependsOn: imagePullDeps.concat([
          await installAuth0UISecret(auth0Client, xns, 'splitwell', 'splitwell'),
        ]),
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
  installSpliceHelmChart(xns, `cluster-ingress-${xns.logicalName}`, 'cn-cluster-ingress-runbook', {
    cluster: {
      hostname: CLUSTER_HOSTNAME,
      svNamespace: xns.logicalName,
    },
    withSvIngress: false,
    ingress: {
      splitwell: splitwell,
      decentralizedSynchronizer: {
        activeMigrationId: decentralizedSynchronizerMigrationConfig.active.id.toString(),
      },
    },
  });
}
