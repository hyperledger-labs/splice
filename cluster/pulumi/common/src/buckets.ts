// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as gcp from '@pulumi/gcp';
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import { ExactNamespace } from '@lfdecentralizedtrust/splice-pulumi-common';

export type GcpBucket = {
  projectId: string;
  bucketName: string;
  secretName: string;
  jsonCredentials: pulumi.Output<string>;
};

export type BucketLocation = {
  bucket: GcpBucket;
  prefix?: string;
};

export type BucketConfig = {
  backupInterval: string;
  location: BucketLocation;
};

export async function bootstrapBucket(
  projectId: string,
  bucketName: string,
  gcpSecretName: string,
  isSharedBucket: boolean = false
): Promise<GcpBucket> {
  const cred = await gcp.secretmanager.getSecretVersion({
    project: isSharedBucket ? projectId : undefined,
    secret: gcpSecretName,
  });
  return {
    projectId,
    bucketName,
    secretName: `cn-gcp-bucket-${projectId}-${bucketName}`,
    jsonCredentials: pulumi.secret(cred.secretData),
  };
}

export function installBucketSecret(xns: ExactNamespace, bucket: GcpBucket): k8s.core.v1.Secret {
  return new k8s.core.v1.Secret(
    `cn-app-${xns.logicalName}-${bucket.secretName}`,
    {
      metadata: {
        name: bucket.secretName,
        namespace: xns.logicalName,
      },
      type: 'Opaque',
      data: {
        'json-credentials': bucket.jsonCredentials.apply(creds =>
          Buffer.from(creds, 'utf-8').toString('base64')
        ),
      },
    },
    {
      dependsOn: [xns.ns],
    }
  );
}
