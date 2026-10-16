// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';
import {
  activeVersion,
  appsKubernetesScheduling,
  CnInput,
  ExactNamespace,
  spliceConfig,
  standardStorageClassName,
} from '@canton-network/splice-pulumi-common';
import { MultiValidatorConfig } from '@canton-network/splice-pulumi-common/src/config/multiValidatorConfig';
import { installSplicePostgres, Postgres } from '@canton-network/splice-pulumi-common/src/postgres';

export function installPostgres(
  xns: ExactNamespace,
  name: string,
  config: MultiValidatorConfig,
  dependsOn: CnInput<pulumi.Resource>[]
): Postgres {
  const secretName = `${name}-secret`;

  return installSplicePostgres(
    xns,
    name,
    secretName,
    config.postgres,
    activeVersion,
    {},
    {
      db: {
        volumeSize: config.postgresPvcSize,
        maxConnections: 1000,
        volumeStorageClass: standardStorageClassName,
        pvcTemplateName: 'pg-data-hd',
      },
      resources: config.resources?.postgres,
      appsAffinityAndTolerations: appsKubernetesScheduling,
    },
    true, // overrideDbSizeFromValues
    false, // useinfraKubernetesScheduling
    {
      dependsOn,
      ...(spliceConfig.pulumiProjectConfig.replacePostgresStatefulSetOnChanges
        ? {
            replaceOnChanges: ['*'],
            deleteBeforeReplace: true,
          }
        : {}),
    }
  );
}
