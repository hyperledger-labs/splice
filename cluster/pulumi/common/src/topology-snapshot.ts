// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { BackupConfig, bootstrapDataBucketSpec } from '@lfdecentralizedtrust/splice-pulumi-common';
import { GCPBucketSchema } from '@lfdecentralizedtrust/splice-pulumi-common-sv';
import { z } from 'zod';

export async function topologySnapshotConfig(
  configuration: z.infer<typeof GCPBucketSchema>,
  prefix: string
): Promise<BackupConfig> {
  const bucketSpec = await bootstrapDataBucketSpec(
    configuration.projectId,
    configuration.bucketName
  );

  // Note that it backups at most once day (also when backupInterval is less than 24h)
  return {
    backupInterval: configuration.backupInterval,
    location: { bucket: bucketSpec, prefix: prefix },
  };
}
