// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import {
  Auth0Config,
  ChartValues,
  CnChartVersion,
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
  const { existingDb } = args;
  const db = existingDb ?? installParticipantPostgres(args);
  const chart = installParticipantChart(args, db, customOptions);
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
};

export type ParticipantOutput = {
  db: Postgres;
  chart: InstalledHelmChart;
};

function installParticipantPostgres({
  xns,
  participant,
  version,
  disableProtection,
}: ParticipantArgs): Postgres {
  return installPostgres(
    xns,
    'participant-pg',
    'participant-pg',
    version,
    participant?.cloudSql ?? spliceConfig.pulumiProjectConfig.cloudSql,
    true,
    { disableProtection }
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
  }: ParticipantArgs,
  db: Postgres,
  customOptions?: SpliceCustomResourceOptions
): InstalledHelmChart {
  const defaultValues: ChartValues = loadDefaultParticipantValues(auth0);

  const { kmsValues, kmsDependencies } = participant?.kms
    ? getParticipantKmsHelmResources(xns, participant.kms)
    : { kmsValues: {}, kmsDependencies: [] };

  const values: ChartValues = {
    ...defaultValues,
    persistence: {
      ...defaultValues.persistence,
      postgresName: db.instanceName,
      host: db.address,
      secretName: db.secretName,
      // the following will not be needed when the MIGRATION_ID gets removed from defaults
      databaseName: 'participant',
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

  return installSpliceHelmChart(xns, `participant`, 'splice-participant', values, version, {
    ...(customOptions ?? {}),
    dependsOn: [...(customOptions?.dependsOn ?? []), db, ...kmsDependencies],
  });
}

function loadDefaultParticipantValues(auth0: Auth0Config): ChartValues {
  return loadYamlFromFile(
    `${SPLICE_ROOT}/apps/app/src/pack/examples/sv-helm/participant-values.yaml`,
    {
      OIDC_AUTHORITY_URL: auth0.auth0Domain,
    }
  );
}
