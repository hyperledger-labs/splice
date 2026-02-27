// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import {
  Auth0Config,
  ChartValues,
  CnChartVersion,
  DomainMigrationIndex,
  ExactNamespace,
  getAdditionalJvmOptions,
  getLedgerApiAudience,
  getParticipantKmsHelmResources,
  InstalledHelmChart,
  installSpliceHelmChart,
  loadYamlFromFile,
  SPLICE_ROOT,
  spliceConfig,
  SpliceCustomResourceOptions,
  standardStorageClassName,
} from '@lfdecentralizedtrust/splice-pulumi-common';
import { SingleSvConfiguration } from '@lfdecentralizedtrust/splice-pulumi-common-sv';
import { installPostgres, Postgres } from '@lfdecentralizedtrust/splice-pulumi-common/src/postgres';

export function installParticipant(
  args: ParticipantArgs,
  customOptions?: SpliceCustomResourceOptions
): ParticipantOutput {
  const { existingDb, migration } = args;
  const db = existingDb ?? installParticipantPostgres(args);
  const chart =
    migration === undefined || migration.isStillRunning
      ? installParticipantChart(args, db, customOptions)
      : undefined;
  return { db, chart };
}

export type ParticipantArgs = {
  xns: ExactNamespace;
  participant: SingleSvConfiguration['participant'];
  logging: SingleSvConfiguration['logging'];
  version: CnChartVersion;
  auth0: Auth0Config;
  existingDb?: Postgres;
  disableProtection?: boolean;
  participantAdminUserNameFrom?: k8s.types.input.core.v1.EnvVarSource;
  imagePullServiceAccountName?: string;
  migration?: {
    id: DomainMigrationIndex;
    isStillRunning: boolean;
  };
};

export type ParticipantOutput = {
  db: Postgres;
  chart?: InstalledHelmChart;
};

function installParticipantPostgres({
  xns,
  participant,
  version,
  disableProtection,
  migration,
}: ParticipantArgs): Postgres {
  return installPostgres(
    xns,
    `participant${migrationSuffix(migration?.id)}-pg`,
    'participant-pg',
    version,
    participant?.cloudSql ?? spliceConfig.pulumiProjectConfig.cloudSql,
    true,
    { isActive: migration?.isStillRunning, migrationId: migration?.id, disableProtection }
  );
}

function installParticipantChart(
  {
    xns,
    participant,
    logging,
    version,
    auth0,
    participantAdminUserNameFrom,
    imagePullServiceAccountName,
    migration,
  }: ParticipantArgs,
  db: Postgres,
  customOptions?: SpliceCustomResourceOptions
): InstalledHelmChart {
  const defaultValues: ChartValues = loadDefaultParticipantValues(auth0);

  const { kmsValues, kmsDependencies } = participant?.kms
    ? getParticipantKmsHelmResources(xns, participant.kms, migration?.id)
    : { kmsValues: {}, kmsDependencies: [] };

  const values: ChartValues = {
    ...defaultValues,
    persistence: {
      ...defaultValues.persistence,
      postgresName: db.instanceName,
      host: db.address,
      secretName: db.secretName,
      // the following will not be needed when the MIGRATION_ID gets removed from defaults
      databaseName: `participant${migrationSuffix(migration?.id, '_')}`,
    },
    auth: {
      ...defaultValues.auth,
      targetAudience: getLedgerApiAudience(auth0, xns.logicalName),
      jwksUrl: `https://${auth0.auth0Domain}/.well-known/jwks.json`,
    },
    ...kmsValues,
    additionalEnvVars: [
      ...(kmsValues.additionalEnvVars ?? []),
      ...(participant?.additionalEnvVars ?? []),
    ],
    logLevel: logging?.cantonLogLevel,
    logLevelStdout: logging?.cantonStdoutLogLevel,
    logAsyncFlush: logging?.cantonAsync,
    participantAdminUserNameFrom,
    metrics: {
      enable: true,
      ...(migration?.id !== undefined
        ? {
            migration: { id: migration.id },
          }
        : {}),
    },
    additionalJvmOptions: getAdditionalJvmOptions(participant?.additionalJvmOptions),
    enablePostgresMetrics: true,
    serviceAccountName: imagePullServiceAccountName,
    resources: participant?.resources,
    pvc: spliceConfig.configuration.persistentHeapDumps
      ? {
          size: '10Gi',
          volumeStorageClass: standardStorageClassName,
        }
      : undefined,
  };

  return installSpliceHelmChart(
    xns,
    `participant${migrationSuffix(migration?.id)}`,
    'splice-participant',
    values,
    version,
    {
      ...(customOptions ?? {}),
      dependsOn: [...(customOptions?.dependsOn ?? []), db, ...kmsDependencies],
    }
  );
}

function loadDefaultParticipantValues(
  auth0: Auth0Config,
  migrationId?: DomainMigrationIndex
): ChartValues {
  return loadYamlFromFile(
    `${SPLICE_ROOT}/apps/app/src/pack/examples/sv-helm/participant-values.yaml`,
    {
      MIGRATION_ID: `${migrationId}`,
      OIDC_AUTHORITY_URL: auth0.auth0Domain,
    }
  );
}

function migrationSuffix(migrationId?: DomainMigrationIndex, separator: string = '-'): string {
  return migrationId !== undefined ? `${separator}${migrationId}` : '';
}
