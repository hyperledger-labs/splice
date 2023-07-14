import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import * as fs from 'fs/promises';
import { Bucket, File, Storage } from '@google-cloud/storage';
import { ExactNamespace, config } from 'cn-pulumi-common';
import { exit } from 'process';

export type GcpBucketConfig = {
  projectId: string;
  bucketName: string;
};

export type GcpBucket = {
  config: GcpBucketConfig;
  secretName: string;
  jsonCredentials: string;
};

export function installGcpBucket(bucketConfig: GcpBucketConfig): GcpBucket {
  return {
    config: bucketConfig,
    secretName: `cn-gcp-bucket-${bucketConfig.projectId}-${bucketConfig.bucketName}`,
    jsonCredentials: config.require('DATA_EXPORT_BUCKET_SA_KEY_JSON'),
  };
}

export type BackupConfig = {
  bucket: GcpBucket;
  backupInterval: string;
  prefix?: string;
};

// Install the bucket's secret into a namespace so apps in there can access the GCP bucket
export function installGcpBucketSecret(xns: ExactNamespace, bucket: GcpBucket): k8s.core.v1.Secret {
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

function getGcpBucket(bucketConfig: GcpBucketConfig, credentials: string): Bucket {
  const storage: Storage = new Storage({
    projectId: bucketConfig.projectId,
    credentials: JSON.parse(credentials),
  });
  return storage.bucket(bucketConfig.bucketName);
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

async function getLatestParticipantIdentityDump(
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

export function getLatestSvcAcsDumpFile(
  xns: ExactNamespace,
  config: BootstrappingDumpConfig
): Promise<File> {
  const bucket = getGcpBucket(config.bucket.config, config.bucket.jsonCredentials);
  return getLatestObjectInDateRange(
    bucket,
    `${bucketPath(config.cluster, xns)}/svc_acs_dump_`,
    '.json',
    config.start,
    config.end
  );
}

export function installParticipantIdentitySecret(
  xns: ExactNamespace,
  secretName: string,
  content: pulumi.Input<string>
): k8s.core.v1.Secret {
  return new k8s.core.v1.Secret(`cn-app-${xns.logicalName}-${secretName}`, {
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

export const participantBootstrapDumpSecretName = 'cn-app-participant-bootstrap-dump';

export type BootstrappingDumpConfig = {
  bucket: GcpBucket;
  cluster: string;
  start: Date;
  end: Date;
};

export function fetchAndInstallParticipantBootstrapDump(
  xns: ExactNamespace,
  config: BootstrappingDumpConfig
): k8s.core.v1.Secret {
  const bucket = getGcpBucket(config.bucket.config, config.bucket.jsonCredentials);
  const content = getLatestParticipantIdentityDump(
    bucket,
    xns,
    config.cluster,
    config.start,
    config.end
  );
  return installParticipantIdentitySecret(xns, participantBootstrapDumpSecretName, content);
}

export async function readAndInstallParticipantBootstrapDump(
  xns: ExactNamespace,
  file: string
): Promise<k8s.core.v1.Secret> {
  const content = await fs.readFile(file, { encoding: 'utf-8' });
  return installParticipantIdentitySecret(xns, participantBootstrapDumpSecretName, content);
}
