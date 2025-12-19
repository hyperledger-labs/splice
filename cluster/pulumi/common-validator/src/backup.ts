// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  BackupConfig,
  bootstrapDataBucketSpec,
  BootstrappingDumpConfig,
  config,
  GcpBucket,
  isDevNet,
} from '@lfdecentralizedtrust/splice-pulumi-common';

type BootstrapCliConfig = {
  cluster: string;
  date: string;
};

const bootstrappingConfig: BootstrapCliConfig = config.optionalEnv('BOOTSTRAPPING_CONFIG')
  ? JSON.parse(config.requireEnv('BOOTSTRAPPING_CONFIG'))
  : undefined;

export async function readBackupConfig(): Promise<{
  periodicBackupConfig?: BackupConfig;
  identitiesBackupLocation: { bucket: GcpBucket };
  bootstrappingDumpConfig?: BootstrappingDumpConfig;
}> {
  let periodicBackupConfig: BackupConfig | undefined;
  let bootstrappingDumpConfig: BootstrappingDumpConfig | undefined;

  // TODO(#3224): Put it in config.yaml like we do for topology snapshots
  const bootstrapBucketSpec = await bootstrapDataBucketSpec(
    config.optionalEnv('DATA_DUMPS_PROJECT') || 'da-cn-devnet',
    config.optionalEnv('DATA_DUMPS_BUCKET') || 'da-cn-data-dumps'
  );
  if (!isDevNet) {
    periodicBackupConfig = { backupInterval: '10m', location: { bucket: bootstrapBucketSpec } };
  }

  if (bootstrappingConfig) {
    const end = new Date(Date.parse(bootstrappingConfig.date));
    // We search within an interval of 24 hours. Given that we usually backups every 10min, this gives us
    // more than enough of a threshold to make sure each node has one backup in that interval
    // while also having sufficiently few backups that the bucket query is fast.
    const start = new Date(end.valueOf() - 24 * 60 * 60 * 1000);
    bootstrappingDumpConfig = {
      bucket: bootstrapBucketSpec,
      cluster: bootstrappingConfig.cluster,
      start,
      end,
    };
  }

  const identitiesBackupLocation = { bucket: bootstrapBucketSpec };
  return {
    periodicBackupConfig,
    bootstrappingDumpConfig,
    identitiesBackupLocation,
  };
}
