import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import { Release } from '@pulumi/kubernetes/helm/v3';
import {
  auth0UserNameEnvVarSource,
  BootstrappingDumpConfig,
  ExactNamespace,
  GlobalDomainMigrationConfig,
  installCNHelmChart,
  installMigrationIdSpecificComponent,
  jmxOptions,
  sanitizedForPostgres,
} from 'cn-pulumi-common';

import * as postgres from './postgres';
import { installPostgresMetrics, Postgres } from './postgres';

export function installMigrationSpecificValidatorParticipant(
  globalDomainMigrationConfig: GlobalDomainMigrationConfig,
  xns: ExactNamespace,
  defaultPostgres: postgres.Postgres | undefined,
  participantBootstrapDump: BootstrappingDumpConfig | undefined,
  dependsOn: pulumi.Resource[] = []
): Release {
  return installMigrationIdSpecificComponent(
    globalDomainMigrationConfig,
    (migrationId, isActive) => {
      const participantPostgres =
        defaultPostgres || postgres.installPostgres(xns, `participant-${migrationId}-pg`, true);

      return installParticipant(
        xns,
        `participant-${migrationId}`,
        participantPostgres,
        auth0UserNameEnvVarSource('validator'),
        // We disable auto-init if we have a dump to bootstrap from.
        !!participantBootstrapDump || !isActive || globalDomainMigrationConfig.isRunningMigration(),
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
      additionalJvmOptions: jmxOptions(),
    },
    {
      dependsOn,
    }
  );

  installPostgresMetrics(postgres, pgName, [participant]);

  return participant;
}
