// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import {
  Auth0Config,
  ChartValues,
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
} from '@lfdecentralizedtrust/splice-pulumi-common';
import { SingleSvConfiguration } from '@lfdecentralizedtrust/splice-pulumi-common-sv';
import { CnChartVersion } from '@lfdecentralizedtrust/splice-pulumi-common/src/artifacts';
import { Postgres } from '@lfdecentralizedtrust/splice-pulumi-common/src/postgres';

import { standardStorageClassName } from '../../common/src/storage/storageClass';

export function installSvParticipant(
  xns: ExactNamespace,
  svConfig: SingleSvConfiguration,
  migrationId: DomainMigrationIndex,
  auth0Config: Auth0Config,
  db: Postgres,
  version: CnChartVersion,
  participantAdminUserNameFrom?: k8s.types.input.core.v1.EnvVarSource,
  imagePullServiceAccountName?: string,
  customOptions?: SpliceCustomResourceOptions
): InstalledHelmChart {
  const name = `participant-${migrationId}`;
  const participantValues: ChartValues = {
    ...loadYamlFromFile(
      `${SPLICE_ROOT}/apps/app/src/pack/examples/sv-helm/participant-values.yaml`,
      {
        MIGRATION_ID: migrationId.toString(),
        OIDC_AUTHORITY_URL: auth0Config.auth0Domain,
      }
    ),
  };

  const { kmsValues, kmsDependencies } = svConfig.participant?.kms
    ? getParticipantKmsHelmResources(xns, svConfig.participant.kms, migrationId)
    : { kmsValues: {}, kmsDependencies: [] };

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
      targetAudience: getLedgerApiAudience(auth0Config, xns.logicalName),
      jwksUrl: `https://${auth0Config.auth0Domain}/.well-known/jwks.json`,
    },
    ...kmsValues,
  };

  return installSpliceHelmChart(
    xns,
    name,
    'splice-participant',
    {
      ...participantValuesWithOverwrites,
      additionalEnvVars: (participantValuesWithOverwrites.additionalEnvVars || []).concat(
        svConfig.participant?.additionalEnvVars || []
      ),
      logLevel: svConfig.logging?.cantonLogLevel,
      logLevelStdout: svConfig.logging?.cantonStdoutLogLevel,
      logAsyncFlush: svConfig.logging?.cantonAsync,
      participantAdminUserNameFrom,
      metrics: {
        enable: true,
        migration: {
          id: migrationId,
        },
      },
      additionalJvmOptions: getAdditionalJvmOptions(svConfig.participant?.additionalJvmOptions),
      enablePostgresMetrics: true,
      serviceAccountName: imagePullServiceAccountName,
      resources: svConfig.participant?.resources,
      pvc: spliceConfig.configuration.persistentHeapDumps
        ? {
            size: '10Gi',
            volumeStorageClass: standardStorageClassName,
          }
        : undefined,
    },
    version,
    {
      ...(customOptions || {}),
      dependsOn: (customOptions?.dependsOn || []).concat([db]).concat(kmsDependencies),
    }
  );
}
