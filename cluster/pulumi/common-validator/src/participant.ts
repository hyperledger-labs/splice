import * as k8s from '@pulumi/kubernetes';
import * as postgres from 'splice-pulumi-common/src/postgres';
import { Output } from '@pulumi/pulumi';
import {
  Auth0Config,
  autoInitValues,
  ChartValues,
  DecentralizedSynchronizerMigrationConfig,
  DEFAULT_AUDIENCE,
  activeVersion,
  DomainMigrationIndex,
  ExactNamespace,
  installSpliceHelmChart,
  jmxOptions,
  loadYamlFromFile,
  LogLevel,
  REPO_ROOT,
  sanitizedForPostgres,
  SpliceCustomResourceOptions,
} from 'splice-pulumi-common';
import { CnChartVersion } from 'splice-pulumi-common/src/artifacts';

export function installParticipant(
  decentralizedSynchronizerMigrationConfig: DecentralizedSynchronizerMigrationConfig,
  migrationId: DomainMigrationIndex,
  xns: ExactNamespace,
  auth0Config: Auth0Config,
  nodeIdentifier: string,
  participantAdminUserNameFrom: k8s.types.input.core.v1.EnvVarSource,
  version: CnChartVersion = activeVersion,
  defaultPostgres?: postgres.Postgres,
  logLevel?: LogLevel,
  customOptions?: SpliceCustomResourceOptions
): { participantAddress: Output<string> } {
  const name = `participant-${migrationId}`;
  const participantPostgres =
    defaultPostgres ||
    postgres.installPostgres(xns, `participant-${migrationId}-pg`, `participant-pg`, true);
  if (decentralizedSynchronizerMigrationConfig.isStillRunning(migrationId)) {
    const participantValues: ChartValues = {
      ...loadYamlFromFile(
        `${REPO_ROOT}/apps/app/src/pack/examples/sv-helm/participant-values.yaml`,
        {
          OIDC_AUTHORITY_URL: auth0Config.auth0Domain,
        }
      ),
      ...loadYamlFromFile(
        `${REPO_ROOT}/apps/app/src/pack/examples/sv-helm/standalone-participant-values.yaml`,
        { MIGRATION_ID: decentralizedSynchronizerMigrationConfig.active.id.toString() }
      ),
      metrics: {
        enable: true,
      },
    };

    const participantValuesWithSpecifiedAud: ChartValues = {
      ...participantValues,
      auth: {
        ...participantValues.auth,
        targetAudience: auth0Config.appToApiAudience['participant'] || DEFAULT_AUDIENCE,
      },
    };

    const pgName = sanitizedForPostgres(name);
    const release = installSpliceHelmChart(
      xns,
      name,
      'cn-participant',
      {
        ...participantValuesWithSpecifiedAud,
        logLevel,
        persistence: {
          databaseName: pgName,
          schema: 'participant',
          host: participantPostgres.address,
          secretName: participantPostgres.secretName,
          postgresName: participantPostgres.instanceName,
        },
        participantAdminUserNameFrom,
        metrics: {
          enable: true,
          migration: {
            id: migrationId,
            active: decentralizedSynchronizerMigrationConfig.active.id === migrationId,
          },
        },
        additionalJvmOptions: jmxOptions(),
        enablePostgresMetrics: true,
        ...autoInitValues('cn-participant', version, nodeIdentifier),
      },
      version,
      {
        ...(customOptions || {}),
        dependsOn: (customOptions?.dependsOn || []).concat([participantPostgres]),
      }
    );
    return {
      participantAddress: release.name,
    };
  } else {
    return {
      participantAddress: Output.create(name),
    };
  }
}
