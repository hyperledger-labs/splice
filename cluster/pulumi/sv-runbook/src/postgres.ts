// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as _ from 'lodash';
import {
  clusterSmallDisk,
  ExactNamespace,
  loadYamlFromFile,
  SPLICE_ROOT,
  supportsSvRunbookReset,
} from '@lfdecentralizedtrust/splice-pulumi-common';
import { spliceConfig } from '@lfdecentralizedtrust/splice-pulumi-common/src/config/config';
import {
  CloudPostgres,
  SplicePostgres,
} from '@lfdecentralizedtrust/splice-pulumi-common/src/postgres';

export function installPostgres(
  xns: ExactNamespace,
  name: string,
  secretName: string,
  selfHostedValuesFile: string,
  isActive: boolean = true
): SplicePostgres | CloudPostgres {
  if (spliceConfig.pulumiProjectConfig.cloudSql.enabled) {
    return new CloudPostgres(
      xns,
      name,
      name,
      secretName,
      spliceConfig.pulumiProjectConfig.cloudSql,
      isActive,
      {
        disableProtection: supportsSvRunbookReset,
      }
    );
  } else {
    const valuesFromFile = loadYamlFromFile(
      `${SPLICE_ROOT}/apps/app/src/pack/examples/sv-helm/${selfHostedValuesFile}`
    );
    const volumeSizeOverride = determineVolumeSizeOverride(valuesFromFile.db?.volumeSize);
    const values = _.merge(valuesFromFile || {}, { db: { volumeSize: volumeSizeOverride } });
    return new SplicePostgres(xns, name, name, secretName, true, values);
  }
}

// A bit complicated because some of the values in our examples are actually lower than the default for CLUSTER_SMALL_DISK
function determineVolumeSizeOverride(volumeSizeFromFile: string | undefined): string | undefined {
  const gigs = (s: string) => parseInt(s.replace('Gi', ''));
  return clusterSmallDisk && volumeSizeFromFile && gigs(volumeSizeFromFile) > 240
    ? '240Gi'
    : undefined;
}
