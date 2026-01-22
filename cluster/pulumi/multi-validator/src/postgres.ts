// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';
import * as random from '@pulumi/random';
import {
  activeVersion,
  appsAffinityAndTolerations,
  CnInput,
  ExactNamespace,
  InstalledHelmChart,
  installPostgresPasswordSecret,
  installSpliceRunbookHelmChart,
  spliceConfig,
  standardStorageClassName,
} from '@lfdecentralizedtrust/splice-pulumi-common';
import { CustomResource } from '@pulumi/kubernetes/apiextensions';

import { hyperdiskSupportConfig } from '../../common/src/config/hyperdiskSupportConfig';
import { multiValidatorConfig } from './config';

export function installPostgres(
  xns: ExactNamespace,
  name: string,
  dependsOn: CnInput<pulumi.Resource>[]
): InstalledHelmChart {
  const password = new random.RandomPassword(`${xns.logicalName}-${name}-passwd`, {
    length: 16,
    overrideSpecial: '_%@',
    special: true,
  }).result;
  const secretName = `${name}-secret`;
  const passwordSecret = installPostgresPasswordSecret(xns, password, secretName);

  if (!multiValidatorConfig) {
    throw new Error('multiValidator config must be set when they are enabled');
  }
  const config = multiValidatorConfig!;

  let hyperdiskMigrationValues = {};
  if (
    hyperdiskSupportConfig.hyperdiskSupport.enabled &&
    hyperdiskSupportConfig.hyperdiskSupport.migrating
  ) {
    const pvcSnapshot = new CustomResource(`pg-data-${xns.logicalName}-${name}-snapshot`, {
      apiVersion: 'snapshot.storage.k8s.io/v1',
      kind: 'VolumeSnapshot',
      metadata: {
        name: `pg-data-${name}-snapshot`,
        namespace: xns.logicalName,
      },
      spec: {
        volumeSnapshotClassName: 'dev-vsc',
        source: {
          persistentVolumeClaimName: `pg-data-${name}-0`,
        },
      },
    });
    hyperdiskMigrationValues = {
      dataSource: {
        kind: 'VolumeSnapshot',
        name: pvcSnapshot.metadata.name,
        apiGroup: 'snapshot.storage.k8s.io',
      },
    };
  }
  return installSpliceRunbookHelmChart(
    xns,
    name,
    'splice-postgres',
    {
      persistence: { secretName },
      db: {
        volumeSize: config.postgresPvcSize,
        maxConnections: 1000,
        ...(hyperdiskSupportConfig.hyperdiskSupport.enabled
          ? {
              volumeStorageClass: standardStorageClassName,
              pvcTemplateName: 'pg-data-hd',
              ...hyperdiskMigrationValues,
            }
          : {}),
      },
      resources: config.resources?.postgres,
      appsAffinityAndTolerations,
    },
    activeVersion,
    {
      dependsOn: [passwordSecret, ...dependsOn],
      ...((hyperdiskSupportConfig.hyperdiskSupport.enabled &&
        // during the migration we first delete the stateful set, which keeps the old pvcs, and the recreate with the new pvcs
        // the stateful sets are immutable so they need to be recreated to force the change of the pvcs
        hyperdiskSupportConfig.hyperdiskSupport.migrating) ||
      spliceConfig.pulumiProjectConfig.replacePostgresStatefulSetOnChanges
        ? {
            replaceOnChanges: ['*'],
            deleteBeforeReplace: true,
          }
        : {}),
    }
  );
}
