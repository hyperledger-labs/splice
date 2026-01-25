// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as gcp from '@pulumi/gcp';
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import * as fs from 'fs/promises';
import { Bucket, File, Storage } from '@google-cloud/storage';
import { CnInput, ExactNamespace, config } from '@lfdecentralizedtrust/splice-pulumi-common';
import { exit } from 'process';

export type GcpBucket = {
  projectId: string;
  bucketName: string;
  secretName: string;
  jsonCredentials: string;
};

export async function bootstrapDataBucketSpec(
  projectId: string,
  bucketName: string
): Promise<GcpBucket> {
  const gcpSecretName = config.requireEnv('DATA_EXPORT_BUCKET_SA_KEY_SECRET');

  const cred = await gcp.secretmanager.getSecretVersion({
    secret: gcpSecretName,
  });

  return {
    projectId,
    bucketName,
    secretName: `cn-gcp-bucket-${projectId}-${bucketName}`,
    jsonCredentials: cred.secretData,
  };
}

export type BackupLocation = {
  bucket: GcpBucket;
  prefix?: string;
};

export type BackupConfig = {
  backupInterval: string;
  location: BackupLocation;
};

// Install the bucket's secret into a namespace so apps in there can access the GCP bucket
export function installBootstrapDataBucketSecret(
  xns: ExactNamespace,
  bucket: GcpBucket
): k8s.core.v1.Secret {
  return new k8s.core.v1.Secret(
    `cn-app-${xns.logicalName}-${bucket.secretName}`,
    {
      metadata: {
        name: bucket.secretName,
        namespace: xns.logicalName,
      },
      type: 'Opaque',
      data: {
        'json-credentials': Buffer.from(bucket.jsonCredentials, 'utf-8').toString('base64'),
      },
    },
    {
      dependsOn: [xns.ns],
    }
  );
}

function openGcpBucket(bucket: GcpBucket): Bucket {
  const storage: Storage = new Storage({
    projectId: bucket.projectId,
    credentials: JSON.parse(bucket.jsonCredentials),
  });
  return storage.bucket(bucket.bucketName);
}

async function fetchBucketFile(bucket: Bucket, file: File): Promise<string> {
  const contents = await bucket.file(file.name).download();
  return contents.toString();
}

async function getLatestObject(
  bucket: Bucket,
  startOffset: string,
  endOffset: string
): Promise<File> {
  const [objects] = await bucket.getFiles({ startOffset, endOffset });
  if (objects.length === 0) {
    console.error(`No files between ${startOffset} and ${endOffset}`);
    exit(1);
  }
  return objects.reduce((prev, cur) => (cur.name > prev.name ? cur : prev));
}

async function getLatestObjectInDateRange(
  bucket: Bucket,
  prefix: string,
  suffix: string,
  start: Date,
  end: Date
): Promise<File> {
  // JS timestamps are miliseconds but Daml timestamps are microseconds so we just add 3 zeroes.
  const startOffset = `${prefix}${start.toISOString().slice(0, -1)}000Z${suffix}`;
  const endOffset = `${prefix}${end.toISOString().slice(0, -1)}000Z${suffix}`;
  return getLatestObject(bucket, startOffset, endOffset);
}

const bucketPath = (cluster: string, xns: ExactNamespace): string => {
  return `${cluster}/${xns.logicalName}`;
};

async function getLatestParticipantIdentitiesDump(
  bucket: Bucket,
  xns: ExactNamespace,
  cluster: string,
  start: Date,
  end: Date
): Promise<string> {
  const latestObject = await getLatestObjectInDateRange(
    bucket,
    `${bucketPath(cluster, xns)}/participant_identities_`,
    '.json',
    start,
    end
  );
  return fetchBucketFile(bucket, latestObject);
}

export function installParticipantIdentitiesSecret(
  xns: ExactNamespace,
  secretName: string,
  content: CnInput<string>
): k8s.core.v1.Secret {
  return new k8s.core.v1.Secret(`splice-app-${xns.logicalName}-${secretName}`, {
    metadata: {
      name: secretName,
      namespace: xns.logicalName,
    },
    type: 'Opaque',
    data: {
      content: pulumi.output(content).apply(c => Buffer.from(c, 'ascii').toString('base64')),
    },
  });
}

export const participantBootstrapDumpSecretName = 'splice-app-participant-bootstrap-dump';

export type BootstrappingDumpConfig = {
  bucket: GcpBucket;
  cluster: string;
  start: Date;
  end: Date;
};

export async function fetchAndInstallParticipantBootstrapDump(
  xns: ExactNamespace,
  config: BootstrappingDumpConfig
): Promise<k8s.core.v1.Secret> {
  const bucket = openGcpBucket(config.bucket);
  const content = await getLatestParticipantIdentitiesDump(
    bucket,
    xns,
    config.cluster,
    config.start,
    config.end
  );
  return installParticipantIdentitiesSecret(xns, participantBootstrapDumpSecretName, content);
}

export async function readAndInstallParticipantBootstrapDump(
  xns: ExactNamespace,
  file: string
): Promise<k8s.core.v1.Secret> {
  const content = await fs.readFile(file, { encoding: 'utf-8' });
  return installParticipantIdentitiesSecret(xns, participantBootstrapDumpSecretName, content);
}
