import * as gcp from '@pulumi/gcp';
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import { Bucket, File, Storage } from '@google-cloud/storage';
import { Key } from '@pulumi/gcp/serviceaccount';
import { Output } from '@pulumi/pulumi';
import { ExactNamespace } from 'cn-pulumi-common';

export type GcpBucketConfig = {
  projectId: string;
  bucketName: string;
};

export type GcpBucket = {
  config: GcpBucketConfig;
  secretName: string;
  jsonCredentials: Output<string>;
};

export function installGcpBucket(config: GcpBucketConfig): GcpBucket {
  const projectId = config.projectId;
  const serviceAccountName = `projects/${projectId}/serviceAccounts/da-cn-data-exports@${projectId}.iam.gserviceaccount.com`;

  // Note, creating a new key can fail with a precondition error on an attempt
  // to create keys beyond the tenth.
  const key: Key = new gcp.serviceaccount.Key(`gcp-bucket-${process.env.GCP_CLUSTER_BASENAME}`, {
    serviceAccountId: serviceAccountName,
    publicKeyType: 'TYPE_X509_PEM_FILE',
  });

  return {
    config: config,
    secretName: `cn-gcp-bucket-${config.projectId}-${config.bucketName}`,
    jsonCredentials: key.privateKey,
  };
}

export type BackupConfig = {
  bucket: GcpBucket;
  backupInterval: string;
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
        'json-credentials': bucket.jsonCredentials,
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
    credentials: JSON.parse(Buffer.from(credentials, 'base64').toString('ascii')),
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
  console.error(startOffset);
  console.error(endOffset);
  return getLatestObject(bucket, startOffset, endOffset);
}

async function getLatestParticipantIdentityDump(
  bucket: Bucket,
  xns: ExactNamespace,
  cluster: string,
  version: string,
  start: Date,
  end: Date
): Promise<string> {
  const latestObject = await getLatestObjectInDateRange(
    bucket,
    `${cluster}/${xns.logicalName}/${version}/participant_identities_`,
    '.json',
    start,
    end
  );
  return fetchBucketFile(bucket, latestObject);
}

export function getLatestSvcAcsDumpFile(
  xns: ExactNamespace,
  config: BootstrappingDumpConfig
): pulumi.Output<File> {
  return config.bucket.jsonCredentials.apply(async credentials => {
    const bucket = getGcpBucket(config.bucket.config, credentials);
    const file = await getLatestObjectInDateRange(
      bucket,
      `${config.cluster}/${xns.logicalName}/${config.version}/svc_acs_dump_`,
      '.json',
      config.start,
      config.end
    );
    return file;
  });
}

export function installParticipantIdentitySecret(
  xns: ExactNamespace,
  secretName: string,
  content: Output<string>
): k8s.core.v1.Secret {
  return new k8s.core.v1.Secret(`cn-app-${xns.logicalName}-${secretName}`, {
    metadata: {
      name: secretName,
      namespace: xns.logicalName,
    },
    type: 'Opaque',
    data: {
      content: content.apply(content => Buffer.from(content, 'ascii').toString('base64')),
    },
  });
}

export const participantBootstrapDumpSecretName = 'cn-app-participant-bootstrap-dump';

export type BootstrappingDumpConfig = {
  bucket: GcpBucket;
  cluster: string;
  version: string;
  start: Date;
  end: Date;
};

export function fetchAndInstallParticipantBootstrapDump(
  xns: ExactNamespace,
  config: BootstrappingDumpConfig
): k8s.core.v1.Secret {
  const dumpContent = config.bucket.jsonCredentials.apply(async credentials => {
    const bucket = getGcpBucket(config.bucket.config, credentials);
    const content = await getLatestParticipantIdentityDump(
      bucket,
      xns,
      config.cluster,
      config.version,
      config.start,
      config.end
    );
    return content;
  });
  return installParticipantIdentitySecret(xns, participantBootstrapDumpSecretName, dumpContent);
}
