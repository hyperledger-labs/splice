// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as postgres from '@lfdecentralizedtrust/splice-pulumi-common/src/postgres';
import {
  activeVersion,
  Auth0Config,
  auth0UserNameEnvVarSource,
  ChartValues,
  DomainMigrationIndex,
  ExactNamespace,
  getAdditionalJvmOptions,
  getParticipantKmsHelmResources,
  installSpliceHelmChart,
  loadYamlFromFile,
  sanitizedForPostgres,
  SPLICE_ROOT,
  SpliceCustomResourceOptions,
  spliceConfig,
  getLedgerApiAudience,
} from '@lfdecentralizedtrust/splice-pulumi-common';
import { ValidatorNodeConfig } from '@lfdecentralizedtrust/splice-pulumi-common-validator';
import { CnChartVersion } from '@lfdecentralizedtrust/splice-pulumi-common/src/artifacts';
import { Output } from '@pulumi/pulumi';

export function installParticipant(
  validatorConfig: ValidatorNodeConfig,
  migrationId: DomainMigrationIndex,
  xns: ExactNamespace,
  auth0Config: Auth0Config,
  disableAuth?: boolean,
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
    postgres.installPostgres(
      xns,
      `participant-pg`,
      `participant-pg`,
      activeVersion,
      spliceConfig.pulumiProjectConfig.cloudSql,
      true
    );
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
      targetAudience: getLedgerApiAudience(auth0Config, xns.logicalName),
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
      logImmediateFlush: validatorConfig.logging?.sync,
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
      additionalJvmOptions: getAdditionalJvmOptions(
        validatorConfig.participant?.additionalJvmOptions
      ),
      additionalEnvVars: (participantValuesWithSpecifiedAud.additionalEnvVars ?? []).concat(
        validatorConfig.participant?.additionalEnvVars ?? []
      ),
      enablePostgresMetrics: true,
      resources: {
        requests: {
          memory: '4Gi',
        },
        limits: {
          memory: '8Gi',
        },
      },
      disableAuth: disableAuth || false,
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
