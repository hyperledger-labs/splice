import * as gcp from '@pulumi/gcp';
import * as k8s from '@pulumi/kubernetes';
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
