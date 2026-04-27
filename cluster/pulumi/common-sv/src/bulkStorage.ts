// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as gcp from '@pulumi/gcp';
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import { ExactNamespace } from '@lfdecentralizedtrust/splice-pulumi-common';
import {
  ClusterBasename,
  GcpRegion,
} from '@lfdecentralizedtrust/splice-pulumi-common/src/config/gcpConfig';

import { BulkStorageConfig } from './singleSvConfig';

export type BulkStorageBucket = {
  bucketName: string;
  region: string;
  secretName: string;
  bucket: gcp.storage.Bucket;
  secret: k8s.core.v1.Secret;
};

export function installScanBulkStorage(
  xns: ExactNamespace,
  bulkStorageConfig: BulkStorageConfig
): BulkStorageBucket | undefined {
  if (!bulkStorageConfig.enabled) {
    return;
  }

  const bucketName = `${ClusterBasename}-${xns.logicalName}-bulk`;
  // TODO(#3429): review other bucket configs
  const bucket = new gcp.storage.Bucket(bucketName, { name: bucketName, location: GcpRegion });
  const bucketServiceAccount = new gcp.serviceaccount.Account(`${bucketName}-sa`, {
    accountId: `${bucketName}-sa`,
    displayName: 'Service Account for Bulk-Storage Bucket Read/Write Access',
  });
  new gcp.storage.BucketIAMMember(
    `${bucketName}-sa-role`,
    {
      bucket: bucket.name,
      role: 'roles/storage.objectUser',
      member: pulumi.interpolate`serviceAccount:${bucketServiceAccount.email}`,
    },
    { dependsOn: [bucket, bucketServiceAccount] }
  );
  const hmacKey = new gcp.storage.HmacKey(
    `${bucketName}-hmac`,
    {
      serviceAccountEmail: bucketServiceAccount.email,
    },
    { dependsOn: [bucketServiceAccount] }
  );

  const accessKey = hmacKey.accessId;
  const secretAccessKey = hmacKey.secret;

  const secretName = 'splice-app-bulk-storage-credentials';
  const secret = new k8s.core.v1.Secret(
    `${xns.logicalName}-bulk-storage-credentials`,
    {
      metadata: {
        name: secretName,
        namespace: xns.logicalName,
      },
      type: 'Opaque',
      data: {
        accessKey: accessKey.apply(k => btoa(k || '')),
        secretAccessKey: secretAccessKey.apply(k => btoa(k || '')),
      },
    },
    {
      dependsOn: [xns.ns, hmacKey],
    }
  );

  return {
    bucketName,
    region: GcpRegion,
    secretName,
    bucket,
    secret
  } as BulkStorageBucket;
}
