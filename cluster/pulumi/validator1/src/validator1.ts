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
  splitwellDarPaths,
  imagePullSecret,
  CnInput,
  DecentralizedSynchronizerMigrationConfig,
  ValidatorTopupConfig,
  ansDomainPrefix,
  DecentralizedSynchronizerUpgradeConfig,
} from 'splice-pulumi-common';
import { installParticipant } from 'splice-pulumi-common-validator';
import {
  AutoAcceptTransfersConfig,
  installValidatorApp,
  installValidatorSecrets,
} from 'splice-pulumi-common-validator/src/validator';

import { validator1Config } from './config';

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
      cometbftPorts: {
        // This ensures the loopback exposes the right ports. We need a +1 since the helm chart does an exclusive range
        domains: DecentralizedSynchronizerUpgradeConfig.highestMigrationId + 1,
      },
    },
    activeVersion,
    { dependsOn: [xns.ns] }
  );

  const kmsConfig = validator1Config?.kms;
  const participantPruningConfig = validator1Config?.participantPruningSchedule;

  const imagePullDeps = imagePullSecret(xns);

  const defaultPostgres = !splitPostgresInstances
    ? postgres.installPostgres(xns, 'postgres', 'postgres', activeVersion, false)
    : undefined;

  const validatorPostgres =
    defaultPostgres ||
    postgres.installPostgres(xns, `validator-pg`, `validator-pg`, activeVersion, true);
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
    kmsConfig,
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
    validatorWalletUsers: pulumi.output([validatorWalletUser]),
    xns,
    dependencies: [],
    ...decentralizedSynchronizerMigrationConfig.migratingNodeConfig(),
    appDars: splitwellDarPaths,
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
    participantAddress: participant.participantAddress,
    topupConfig,
    svValidator: false,
    scanAddress,
    secrets: validatorSecrets,
    autoAcceptTransfers: autoAcceptTransfers,
    nodeIdentifier: 'validator1',
    participantPruningConfig,
    deduplicationDuration: validator1Config?.deduplicationDuration,
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
      spliceDomainNames: {
        nameServiceDomain: ansDomainPrefix,
      },
      ingress: {
        splitwell: splitwell,
        decentralizedSynchronizer: {
          activeMigrationId: decentralizedSynchronizerMigrationConfig.active.id.toString(),
        },
      },
    }
  );
}
