import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import { Release } from '@pulumi/kubernetes/helm/v3';
import {
  auth0UserNameEnvVarSource,
  BootstrappingDumpConfig,
  CnChartVersion,
  disableCantonAutoInit,
  ExactNamespace,
  DecentralizedSynchronizerMigrationConfig,
  installCNHelmChart,
  installMigrationIdSpecificComponent,
  jmxOptions,
  sanitizedForPostgres,
} from 'cn-pulumi-common';

import * as postgres from '../../common/src/postgres';
import { installPostgresMetrics, Postgres } from '../../common/src/postgres';

export function installMigrationSpecificValidatorParticipant(
  decentralizedSynchronizerMigrationConfig: DecentralizedSynchronizerMigrationConfig,
  xns: ExactNamespace,
  defaultPostgres: postgres.Postgres | undefined,
  participantBootstrapDump: BootstrappingDumpConfig | undefined,
  nodeIdentifier: string,
  dependsOn: pulumi.Resource[] = []
): Release {
  return installMigrationIdSpecificComponent(
    decentralizedSynchronizerMigrationConfig,
    (migrationId, isActive, version) => {
      const participantPostgres =
        defaultPostgres || postgres.installPostgres(xns, `participant-${migrationId}-pg`, true);

      return installParticipant(
        xns,
        `participant-${migrationId}`,
        participantPostgres,
        auth0UserNameEnvVarSource('validator'),
        // We disable auto-init if we have a dump to bootstrap from.
        disableCantonAutoInit ||
          !!participantBootstrapDump ||
          !isActive ||
          decentralizedSynchronizerMigrationConfig.isRunningMigration(),
        nodeIdentifier,
        version,
        dependsOn
      );
    }
  ).activeComponent;
}

export function installParticipant(
  xns: ExactNamespace,
  name: string,
  postgres: Postgres,
  participantAdminUserNameFrom: k8s.types.input.core.v1.EnvVarSource,
  disableAutoInit = false,
  nodeIdentifier: string,
  version: CnChartVersion,
  dependsOn: pulumi.Resource[] = []
): Release {
  const pgName = sanitizedForPostgres(name);

  const participant = installCNHelmChart(
    xns,
    name,
    'cn-participant',
    {
      persistence: {
        databaseName: pgName,
        schema: 'participant',
        host: postgres.address,
        secretName: postgres.secretName,
      },
      participantAdminUserNameFrom,
      disableAutoInit,
      metrics: {
        enable: true,
      },
      nodeIdentifier,
      additionalJvmOptions: jmxOptions(),
    },
    version,
    {
      dependsOn,
    }
  );

  installPostgresMetrics(postgres, pgName, [participant]);

  return participant;
}
