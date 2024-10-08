import * as postgres from 'splice-pulumi-common/src/postgres';
import { Output } from '@pulumi/pulumi';
import {
  Auth0Config,
  auth0UserNameEnvVarSource,
  autoInitValues,
  ChartValues,
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
  migrationId: DomainMigrationIndex,
  xns: ExactNamespace,
  auth0Config: Auth0Config,
  nodeIdentifier: string,
  version: CnChartVersion = activeVersion,
  defaultPostgres?: postgres.Postgres,
  logLevel?: LogLevel,
  customOptions?: SpliceCustomResourceOptions
): { participantAddress: Output<string> } {
  const participantPostgres =
    defaultPostgres || postgres.installPostgres(xns, `participant-pg`, `participant-pg`, true);
  const participantValues: ChartValues = {
    ...loadYamlFromFile(`${REPO_ROOT}/apps/app/src/pack/examples/sv-helm/participant-values.yaml`, {
      OIDC_AUTHORITY_URL: auth0Config.auth0Domain,
    }),
    ...loadYamlFromFile(
      `${REPO_ROOT}/apps/app/src/pack/examples/sv-helm/standalone-participant-values.yaml`,
      { MIGRATION_ID: migrationId.toString() }
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

  const name = `participant-${migrationId}`;
  const pgName = sanitizedForPostgres(name);
  const release = installSpliceHelmChart(
    xns,
    name,
    'splice-participant',
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
      participantAdminUserNameFrom: auth0UserNameEnvVarSource('validator'),
      metrics: {
        enable: true,
        migration: {
          id: migrationId,
          active: true,
        },
      },
      additionalJvmOptions: jmxOptions(),
      enablePostgresMetrics: true,
      ...autoInitValues('splice-participant', version, nodeIdentifier),
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
}
