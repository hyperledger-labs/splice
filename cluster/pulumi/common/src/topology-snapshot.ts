// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { TopologySnapshotSchema } from '@lfdecentralizedtrust/splice-pulumi-common-sv';
import { z } from 'zod';

import { bootstrapBucket, BucketConfig } from './buckets';
import { config } from './config';

export async function topologySnapshotConfig(
  configuration: z.infer<typeof TopologySnapshotSchema>,
  prefix: string
): Promise<BucketConfig> {
  const gcpSecretName = config.requireEnv('TOPOLOGY_SNAPSHOT_BUCKET_SA_KEY_SECRET');
  const bucketSpec = await bootstrapBucket(
    configuration.projectId,
    configuration.bucketName,
    gcpSecretName
  );
  return {
    backupInterval: configuration.backupInterval,
    location: { bucket: bucketSpec, prefix: prefix },
  };
}
