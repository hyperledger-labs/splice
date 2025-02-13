import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import {
  Auth0Config,
  ChartValues,
  DEFAULT_AUDIENCE,
  DomainMigrationIndex,
  ExactNamespace,
  InstalledHelmChart,
  installSpliceHelmChart,
  jmxOptions,
  loadYamlFromFile,
  REPO_ROOT,
  SpliceCustomResourceOptions,
} from 'splice-pulumi-common';
import { CnChartVersion } from 'splice-pulumi-common/src/artifacts';
import { Postgres } from 'splice-pulumi-common/src/postgres';

export interface SvParticipant {
  readonly asDependencies: pulumi.Resource[];
  readonly internalClusterAddress: pulumi.Output<string>;
}

export function installSvParticipant(
  xns: ExactNamespace,
  migrationId: DomainMigrationIndex,
  auth0Config: Auth0Config,
  isActive: boolean,
  db: Postgres,
  logLevel: string,
  version: CnChartVersion,
  onboardingName: string,
  participantAdminUserNameFrom?: k8s.types.input.core.v1.EnvVarSource,
  customOptions?: SpliceCustomResourceOptions
): InstalledHelmChart {
  const name = `participant-${migrationId}`;
  const participantValues: ChartValues = {
    ...loadYamlFromFile(`${REPO_ROOT}/apps/app/src/pack/examples/sv-helm/participant-values.yaml`, {
      MIGRATION_ID: migrationId.toString(),
      OIDC_AUTHORITY_URL: auth0Config.auth0Domain,
    }),
    metrics: {
      enable: true,
      migration: {
        id: migrationId,
        active: isActive,
      },
    },
  };

  const participantValuesWithOverwrites: ChartValues = {
    ...participantValues,
    ...{
      persistence: {
        ...participantValues.persistence,
        postgresName: db.instanceName,
        host: db.address,
        secretName: db.secretName,
      },
    },
    auth: {
      ...participantValues.auth,
      targetAudience: auth0Config.appToApiAudience['participant'] || DEFAULT_AUDIENCE,
      jwksUrl: `https://${auth0Config.auth0Domain}/.well-known/jwks.json`,
    },
  };

  return installSpliceHelmChart(
    xns,
    name,
    'splice-participant',
    {
      ...participantValuesWithOverwrites,
      logLevel,
      participantAdminUserNameFrom,
      metrics: {
        enable: true,
        migration: {
          id: migrationId,
          active: isActive,
        },
      },
      additionalJvmOptions: jmxOptions(),
      enablePostgresMetrics: true,
    },
    version,
    {
      ...(customOptions || {}),
      dependsOn: (customOptions?.dependsOn || []).concat([db]),
      // TODO(#14507) - remove alias once latest release is 0.2.0
      aliases: [{ name: `participant-${migrationId}` }],
    }
  );
}
