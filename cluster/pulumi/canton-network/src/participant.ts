import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import { Release } from '@pulumi/kubernetes/helm/v3';
import {
  auth0UserNameEnvVarSource,
  BootstrappingDumpConfig,
  disableCantonAutoInit,
  ExactNamespace,
  DecentralizedSynchronizerMigrationConfig,
  installCNHelmChart,
  installMigrationIdSpecificComponent,
  jmxOptions,
  sanitizedForPostgres,
  Auth0Config,
  LogLevel,
  DomainMigrationIndex,
} from 'cn-pulumi-common';
import { CnChartVersion } from 'cn-pulumi-common/src/artifacts';

import * as postgres from '../../common/src/postgres';
import { Postgres } from '../../common/src/postgres';

export function installMigrationSpecificValidatorParticipant(
  decentralizedSynchronizerMigrationConfig: DecentralizedSynchronizerMigrationConfig,
  xns: ExactNamespace,
  defaultPostgres: postgres.Postgres | undefined,
  participantBootstrapDump: BootstrappingDumpConfig | undefined,
  nodeIdentifier: string,
  auth0Cfg: Auth0Config,
  logLevel?: LogLevel,
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
        auth0Cfg,
        migrationId,
        isActive,
        logLevel,
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
  auth0Cfg: Auth0Config,
  migrationId: DomainMigrationIndex,
  isActiveDomain: boolean,
  logLevel?: LogLevel,
  dependsOn: pulumi.Resource[] = []
): Release {
  const pgName = sanitizedForPostgres(name);

  const participant = installCNHelmChart(
    xns,
    name,
    'cn-participant',
    {
      logLevel,
      persistence: {
        databaseName: pgName,
        schema: 'participant',
        host: postgres.address,
        secretName: postgres.secretName,
        postgresName: postgres.name,
      },
      participantAdminUserNameFrom,
      disableAutoInit,
      metrics: {
        enable: true,
        migration: {
          id: migrationId,
          active: isActiveDomain,
        },
      },
      nodeIdentifier,
      additionalJvmOptions: jmxOptions(),
      enablePostgresMetrics: true,
      auth: {
        jwksUrl: `https://${auth0Cfg.auth0Domain}/.well-known/jwks.json`,
        targetAudience: auth0Cfg.appToApiAudience['participant'],
      },
    },
    version,
    {
      dependsOn,
    }
  );

  return participant;
}
