// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
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
  SpliceCustomResourceOptions,
} from '@lfdecentralizedtrust/splice-pulumi-common';
import { CnChartVersion } from '@lfdecentralizedtrust/splice-pulumi-common/src/artifacts';
import { Postgres } from '@lfdecentralizedtrust/splice-pulumi-common/src/postgres';

import { SingleSvConfiguration } from './singleSvConfig';

export interface SvParticipant {
  readonly asDependencies: pulumi.Resource[];
  readonly internalClusterAddress: pulumi.Output<string>;
}

export function installSvParticipant(
  xns: ExactNamespace,
  svConfig: SingleSvConfiguration,
  migrationId: DomainMigrationIndex,
  auth0Config: Auth0Config,
  isActive: boolean,
  db: Postgres,
  version: CnChartVersion,
  onboardingName: string,
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
      targetAudience: getLedgerApiAudience(auth0Config),
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
    },
    version,
    {
      ...(customOptions || {}),
      dependsOn: (customOptions?.dependsOn || []).concat([db]).concat(kmsDependencies),
    }
  );
}
