import * as pulumi from '@pulumi/pulumi';
import * as postgres from 'splice-pulumi-common/src/postgres';
import {
  Auth0Client,
  BackupConfig,
  BootstrappingDumpConfig,
  CLUSTER_HOSTNAME,
  activeVersion,
  ExactNamespace,
  exactNamespace,
  installAuth0UISecret,
  installSpliceHelmChart,
  spliceInstanceNames,
  splitwellDarPath,
  imagePullSecret,
  CnInput,
  DecentralizedSynchronizerMigrationConfig,
  ValidatorTopupConfig,
} from 'splice-pulumi-common';
import { installParticipant } from 'splice-pulumi-common-validator';
import {
  AutoAcceptTransfersConfig,
  installValidatorApp,
  installValidatorSecrets,
} from 'splice-pulumi-common-validator/src/validator';
import { spliceEnvConfig } from 'splice-pulumi-common/src/config/envConfig';

export async function installValidator1(
  auth0Client: Auth0Client,
  name: string,
  onboardingSecret: string,
  validatorWalletUser: string,
  splitPostgresInstances: boolean,
  decentralizedSynchronizerMigrationConfig: DecentralizedSynchronizerMigrationConfig,
  installSplitwell: boolean,
  backupConfig?: BackupConfig,
  participantBootstrapDump?: BootstrappingDumpConfig,
  topupConfig?: ValidatorTopupConfig,
  autoAcceptTransfers?: AutoAcceptTransfersConfig
): Promise<pulumi.Resource> {
  const xns = exactNamespace(name, true);

  const loopback = installSpliceHelmChart(
    xns,
    'loopback',
    'splice-cluster-loopback-gateway',
    {
      cluster: {
        hostname: CLUSTER_HOSTNAME,
      },
    },
    activeVersion,
    { dependsOn: [xns.ns] }
  );
  const imagePullDeps = imagePullSecret(xns);

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

  const participantDependsOn: CnInput<pulumi.Resource>[] = imagePullDeps.concat([loopback]);

  const participant = installParticipant(
    decentralizedSynchronizerMigrationConfig.active.id,
    xns,
    auth0Client.getCfg(),
    'validator1',
    decentralizedSynchronizerMigrationConfig.active.version,
    defaultPostgres,
    undefined,
    {
      dependsOn: participantDependsOn,
    }
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
    validatorPartyHint: spliceEnvConfig.envFlag('VALIDATOR_LEGACY_PARTY_HINT')
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
      'splice-splitwell-web-ui',
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
  installSpliceHelmChart(
    xns,
    `cluster-ingress-${xns.logicalName}`,
    'splice-cluster-ingress-runbook',
    {
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
    }
  );
}
