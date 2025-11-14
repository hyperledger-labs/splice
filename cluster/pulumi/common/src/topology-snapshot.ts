// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  config,
  BackupConfig,
  bootstrapDataBucketSpec,
} from '@lfdecentralizedtrust/splice-pulumi-common';

export async function topologySnapshotConfig(prefix: string): Promise<BackupConfig> {
  const bucketSpec = await bootstrapDataBucketSpec(
    config.optionalEnv('TOPOLOGY_SNAPSHOT_PROJECT') || 'da-cn-devnet',
    config.optionalEnv('TOPOLOGY_SNAPSHOT_BUCKET') || 'da-cn-topology-snapshots'
  );

  return { backupInterval: '2h', location: { bucket: bucketSpec, prefix: prefix } };
}
