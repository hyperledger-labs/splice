// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';
import * as random from '@pulumi/random';
import {
  activeVersion,
  CnInput,
  ExactNamespace,
  installSpliceRunbookHelmChart,
  installPostgresPasswordSecret,
  InstalledHelmChart,
  nonHyperdiskAppsAffinityAndTolerations,
} from '@lfdecentralizedtrust/splice-pulumi-common';

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

  return installSpliceRunbookHelmChart(
    xns,
    name,
    'splice-postgres',
    {
      persistence: { secretName },
      db: { volumeSize: config.postgresPvcSize, maxConnections: 1000 },
      resources: config.resources?.postgres,
      nonHyperdiskAppsAffinityAndTolerations,
    },
    activeVersion,
    { dependsOn: [passwordSecret, ...dependsOn] }
  );
}
