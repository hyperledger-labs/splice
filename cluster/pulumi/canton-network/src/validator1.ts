import * as pulumi from '@pulumi/pulumi';
import {
  Auth0Client,
  auth0UserNameEnvVarSource,
  BackupConfig,
  BootstrappingDumpConfig,
  CLUSTER_BASENAME,
  ExactNamespace,
  exactNamespace,
  GlobalDomainMigrationConfig,
  installAuth0UISecret,
  installCNHelmChart,
  installMigrationIdSpecificComponent,
  ValidatorTopupConfig,
} from 'cn-pulumi-common';

import * as postgres from './postgres';
import { installParticipant } from './ledger';
import { installPostgresMetrics } from './postgres';
import { installValidatorApp, installValidatorSecrets } from './validator';

export async function installValidator1(
  auth0Client: Auth0Client,
  name: string,
  onboardingSecret: string,
  validatorWalletUser: string,
  splitPostgresInstances: boolean,
  globalDomainMigrationConfig: GlobalDomainMigrationConfig,
  installSplitwell: boolean,
  dependsOn: pulumi.Resource[],
  backupConfig?: BackupConfig,
  participantBootstrapDump?: BootstrappingDumpConfig,
  topupConfig?: ValidatorTopupConfig
): Promise<pulumi.Resource> {
  const xns = exactNamespace(name, true);

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

  const defaultPostgres = !splitPostgresInstances
    ? postgres.installPostgres(xns, 'postgres', false)
    : undefined;

  const validatorPostgres = defaultPostgres || postgres.installPostgres(xns, `validator-pg`, true);
  const validatorDbName = `validator1`;

  const validatorSecrets = await installValidatorSecrets({
    xns,
    auth0Client,
    auth0AppName: 'validator1',
  });

  const participant = installMigrationIdSpecificComponent(
    globalDomainMigrationConfig,
    (migrationId, isActive) => {
      const participantPostgres = splitPostgresInstances
        ? postgres.installPostgres(xns, `participant-${migrationId}-pg`, true)
        : (defaultPostgres as postgres.Postgres);

      return installParticipant(
        xns,
        `participant-${migrationId}`,
        participantPostgres,
        auth0UserNameEnvVarSource('validator'),
        // We disable auto-init if we have a dump to bootstrap from.
        !!participantBootstrapDump || !isActive || globalDomainMigrationConfig.isRunningMigration(),
        [loopback]
      );
    }
  ).activeComponent;

  const extraDependsOn: pulumi.Resource[] = dependsOn.concat([participant, validatorPostgres]);
  const globalDomainUrl = `https://sequencer-${globalDomainMigrationConfig.activeMigrationId}.sv-1.svc.${CLUSTER_BASENAME}.network.canton.global`;
  const scanAddress = `http://scan-app.sv-1:5012`;

  const validator = await installValidatorApp({
    validatorWalletUser,
    xns,
    participant,
    ...globalDomainMigrationConfig.migratingNodeConfig(),
    // We vet both versions to easily test upgrades.
    appDars: [
      'cn-node-0.1.0-SNAPSHOT/dars/splitwell-0.1.0.dar',
      'cn-node-0.1.0-SNAPSHOT/dars/splitwell-0.2.0.dar',
    ],
    validatorPartyHint: `${name}_validator_service_user`,
    svSponsorAddress: `http://sv-app.sv-1:5014`,
    onboardingSecret,
    persistenceConfig: {
      host: validatorPostgres.address,
      databaseName: pulumi.Output.create(validatorDbName),
      secretName: validatorPostgres.secretName,
      schema: pulumi.Output.create(validatorDbName),
      user: pulumi.Output.create('cnadmin'),
      port: pulumi.Output.create(5432),
    },
    backupConfig: backupConfig ? { config: backupConfig } : undefined,
    extraDependsOn,
    participantBootstrapDump,
    participantAddress: participant.name,
    topupConfig,
    svValidator: false,
    globalDomainUrl,
    scanAddress,
    secrets: validatorSecrets,
  });

  installPostgresMetrics(validatorPostgres, validatorDbName, [validator]);
  installIngress(xns, installSplitwell);

  if (installSplitwell) {
    installCNHelmChart(
      xns,
      'splitwell-web-ui',
      'cn-splitwell-web-ui',
      {},
      {
        dependsOn: [await installAuth0UISecret(auth0Client, xns, 'splitwell', 'splitwell')],
      }
    );
  }

  return validator;
}

function installIngress(xns: ExactNamespace, spliwell: boolean) {
  installCNHelmChart(
    xns,
    `cluster-ingress-${xns.logicalName}`,
    'cn-cluster-ingress-runbook',
    {
      cluster: {
        hostname: `${CLUSTER_BASENAME}.network.canton.global`,
        hostPrefix: '',
        svNamespace: xns.logicalName,
      },
      withSvIngress: false,
      ingress: {
        splitwell: spliwell,
      },
    },
    {}
  );
}
