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

  const validatorSecrets = await installValidatorSecrets({
    xns,
    auth0Client,
    auth0AppName: 'validator1',
  });

  const validator = await installMigrationIdSpecificComponent(
    globalDomainMigrationConfig,
    (migrationId, isActive) => {
      const participantPostgres = splitPostgresInstances
        ? postgres.installPostgres(xns, `participant-${migrationId}-pg`, true)
        : (defaultPostgres as postgres.Postgres);

      const participant = installParticipant(
        xns,
        `participant-${migrationId}`,
        participantPostgres,
        auth0UserNameEnvVarSource('validator'),
        // We disable auto-init if we have a dump to bootstrap from.
        !!participantBootstrapDump || !isActive,
        [loopback]
      );

      const validatorPostgres = splitPostgresInstances
        ? postgres.installPostgres(xns, `validator-${migrationId}-pg`, true)
        : participantPostgres;

      const validatorDbName = `validator1_${migrationId}`;

      const extraDependsOn: pulumi.Resource[] = dependsOn.concat([
        participantPostgres,
        validatorPostgres,
      ]);
      const globalDomainUrl = `https://sequencer-${migrationId}.sv-1.svc.${CLUSTER_BASENAME}.network.canton.global`;
      const scanAddress = `http://scan-app-${migrationId}.sv-1:5012`;

      const validator = installValidatorApp({
        validatorWalletUser,
        xns,
        participant,
        domainMigrationId: migrationId,
        globalDomainMigrationConfig: globalDomainMigrationConfig,
        // We vet both versions to easily test upgrades.
        appDars: [
          'cn-node-0.1.0-SNAPSHOT/dars/splitwell-0.1.0.dar',
          'cn-node-0.1.0-SNAPSHOT/dars/splitwell-0.2.0.dar',
        ],
        validatorPartyHint: `${name}_validator_service_user`,
        extraDomains:
          isActive && installSplitwell
            ? [
                {
                  alias: 'splitwell',
                  url: 'http://domain.splitwell:5008',
                },
              ]
            : [],
        svSponsorAddress: `http://sv-app-${migrationId}.sv-1:5014`,
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
      installIngress(xns, installSplitwell, {
        type: 'migration-specific',
        migrationId: migrationId.toString(),
      });
      return validator;
    }
  );

  installIngress(xns, installSplitwell, {
    type: 'active',
    activeMigrationId: globalDomainMigrationConfig.activeMigrationId.toString(),
  });

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

function installIngress(
  xns: ExactNamespace,
  spliwell: boolean,
  globalDomainConfig:
    | { type: 'migration-specific'; migrationId: string }
    | {
        type: 'active';
        activeMigrationId: string;
      }
) {
  const name =
    globalDomainConfig.type == 'migration-specific'
      ? `cluster-ingress-validator1-${globalDomainConfig.migrationId}`
      : `cluster-ingress-validator1-active`;
  installCNHelmChart(
    xns,
    name,
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
        globalDomain: globalDomainConfig,
      },
    },
    {}
  );
}
