// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';
import { exit } from 'process';

import {
  BackupConfig,
  bootstrapDataBucketSpec,
  fetchAndInstallParticipantBootstrapDump,
  installBootstrapDataBucketSecret,
  readAndInstallParticipantBootstrapDump,
} from './backup';
import { isDevNet } from './config';
import { ExactNamespace } from './utils';

type BootstrapCliConfig = {
  cluster: string;
  date: string;
};

type BootstrapParams = {
  xns: ExactNamespace;
  namespace: string;
  CLUSTER_BASENAME: string;
  participantIdentitiesFile?: string;
  bootstrappingConfig: BootstrapCliConfig;
};

type BootstrapResources = {
  participantBootstrapDumpSecret: pulumi.Resource | undefined;
  backupConfigSecret: pulumi.Resource | undefined;
  backupConfig: BackupConfig | undefined;
};

export async function setupBootstrapping(config: BootstrapParams): Promise<BootstrapResources> {
  const { xns, namespace, CLUSTER_BASENAME, participantIdentitiesFile, bootstrappingConfig } =
    config;

  if (participantIdentitiesFile && bootstrappingConfig) {
    console.error(
      `We can restore participant identities from *either* a file or from GCP,` +
        `but both PARTICIPANT_IDENTITIES_FILE and BOOTSTRAPPING_CONFIG have been set.`
    );
    exit(1);
  } else if (participantIdentitiesFile) {
    console.error(`Bootstrapping participant identity from file ${participantIdentitiesFile}`);
  } else if (bootstrappingConfig) {
    console.error(`Bootstrapping participant identity from cluster ${bootstrappingConfig.cluster}`);
  } else {
    console.error(`Bootstraping participant with fresh identity`);
  }

  let participantBootstrapDumpSecret: pulumi.Resource | undefined;
  let backupConfigSecret: pulumi.Resource | undefined;
  let backupConfig: BackupConfig | undefined;

  const bootstrapBucketSpec = await bootstrapDataBucketSpec('da-cn-devnet', 'da-cn-data-dumps');

  if (bootstrappingConfig || !isDevNet) {
    backupConfig = {
      backupInterval: '10m',
      location: {
        bucket: bootstrapBucketSpec,
        prefix: `${CLUSTER_BASENAME}/${namespace}`,
      },
    };
    backupConfigSecret = installBootstrapDataBucketSecret(xns, backupConfig.location.bucket);
  }

  if (participantIdentitiesFile) {
    participantBootstrapDumpSecret = await readAndInstallParticipantBootstrapDump(
      xns,
      participantIdentitiesFile
    );
  } else if (bootstrappingConfig) {
    const end = new Date(Date.parse(bootstrappingConfig.date));
    // We search within an interval of 24 hours. Given that we usually backups every 10min, this gives us
    // more than enough of a threshold to make sure each node has one backup in that interval
    // while also having sufficiently few backups that the bucket query is fast.
    const start = new Date(end.valueOf() - 24 * 60 * 60 * 1000);
    const bootstrappingDumpConfig = {
      bucket: bootstrapBucketSpec,
      cluster: bootstrappingConfig.cluster,
      start,
      end,
    };
    participantBootstrapDumpSecret = await fetchAndInstallParticipantBootstrapDump(
      xns,
      bootstrappingDumpConfig
    );
  }

  return {
    participantBootstrapDumpSecret,
    backupConfigSecret,
    backupConfig,
  };
}
