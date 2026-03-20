// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { TopologySnapshotSchema } from '@lfdecentralizedtrust/splice-pulumi-common-sv';
import { z } from 'zod';

import { bootstrapBucket, BucketConfig } from './buckets';

export async function topologySnapshotConfig(
  configuration: z.infer<typeof TopologySnapshotSchema>,
  prefix: string
): Promise<BucketConfig> {
  const bucketSpec = await bootstrapBucket(
    configuration.projectId,
    configuration.bucketName,
    configuration.bucketSaKeySecret
  );
  return {
    backupInterval: configuration.backupInterval,
    location: { bucket: bucketSpec, prefix: prefix },
  };
}
