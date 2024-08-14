import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import { Release } from '@pulumi/kubernetes/helm/v3';
import {
  auth0UserNameEnvVarSource,
  BootstrappingDumpConfig,
  ExactNamespace,
  DecentralizedSynchronizerMigrationConfig,
  installCNHelmChart,
  installMigrationIdSpecificComponent,
  jmxOptions,
  sanitizedForPostgres,
  Auth0Config,
  LogLevel,
  DomainMigrationIndex,
  autoInitValues,
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
  const participantPostgres =
    defaultPostgres ||
    postgres.installPostgres(
      xns,
      'participant-pg',
      `participant-${decentralizedSynchronizerMigrationConfig.active.migrationId}-pg`,
      true
    );
  return installMigrationIdSpecificComponent(
    decentralizedSynchronizerMigrationConfig,
    (migrationId, isActive, version) => {
      return installParticipant(
        xns,
        `participant-${migrationId}`,
        participantPostgres,
        auth0UserNameEnvVarSource('validator'),
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
        postgresName: postgres.instanceName,
      },
      participantAdminUserNameFrom,
      metrics: {
        enable: true,
        migration: {
          id: migrationId,
          active: isActiveDomain,
        },
      },
      additionalJvmOptions: jmxOptions(),
      enablePostgresMetrics: true,
      auth: {
        jwksUrl: `https://${auth0Cfg.auth0Domain}/.well-known/jwks.json`,
        targetAudience: auth0Cfg.appToApiAudience['participant'],
      },
      ...autoInitValues(version, nodeIdentifier),
    },
    version,
    {
      dependsOn: dependsOn.concat([postgres]),
    }
  );

  return participant;
}
