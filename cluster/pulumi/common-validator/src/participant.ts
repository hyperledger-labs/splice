// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as postgres from 'splice-pulumi-common/src/postgres';
import { Output } from '@pulumi/pulumi';
import {
  activeVersion,
  Auth0Config,
  auth0UserNameEnvVarSource,
  ChartValues,
  DEFAULT_AUDIENCE,
  DomainMigrationIndex,
  ExactNamespace,
  getParticipantKmsHelmResources,
  installSpliceHelmChart,
  jmxOptions,
  loadYamlFromFile,
  sanitizedForPostgres,
  SPLICE_ROOT,
  SpliceCustomResourceOptions,
} from 'splice-pulumi-common';
import { ValidatorNodeConfig } from 'splice-pulumi-common-validator';
import { CnChartVersion } from 'splice-pulumi-common/src/artifacts';

export function installParticipant(
  validatorConfig: ValidatorNodeConfig,
  migrationId: DomainMigrationIndex,
  xns: ExactNamespace,
  auth0Config: Auth0Config,
  nodeIdentifier: string,
  version: CnChartVersion = activeVersion,
  defaultPostgres?: postgres.Postgres,
  customOptions?: SpliceCustomResourceOptions
): { participantAddress: Output<string> } {
  const kmsConfig = validatorConfig.kms;
  const { kmsValues, kmsDependencies } = kmsConfig
    ? getParticipantKmsHelmResources(xns, kmsConfig)
    : { kmsValues: {}, kmsDependencies: [] };

  const participantPostgres =
    defaultPostgres ||
    postgres.installPostgres(xns, `participant-pg`, `participant-pg`, activeVersion, true);
  const participantValues: ChartValues = {
    ...loadYamlFromFile(
      `${SPLICE_ROOT}/apps/app/src/pack/examples/sv-helm/participant-values.yaml`,
      {
        OIDC_AUTHORITY_URL: auth0Config.auth0Domain,
      }
    ),
    ...loadYamlFromFile(
      `${SPLICE_ROOT}/apps/app/src/pack/examples/sv-helm/standalone-participant-values.yaml`,
      { MIGRATION_ID: migrationId.toString() }
    ),
    ...kmsValues,
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
      logLevel: validatorConfig.logging?.level,
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
      resources: {
        requests: {
          memory: '4Gi',
        },
        limits: {
          memory: '8Gi',
        },
      },
    },
    version,
    {
      ...(customOptions || {}),
      dependsOn: (customOptions?.dependsOn || [])
        .concat([participantPostgres])
        .concat(kmsDependencies),
    }
  );
  return {
    participantAddress: release.name,
  };
}
