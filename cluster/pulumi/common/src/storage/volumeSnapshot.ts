// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { CustomResource } from '@pulumi/kubernetes/apiextensions';
import { Output } from '@pulumi/pulumi';

export interface VolumeSnapshotOptions {
  resourceName: string;
  snapshotName: string;
  namespace: string;
  pvcName: string;
  volumeSnapshotClassName?: string;
}
export function createVolumeSnapshot(options: VolumeSnapshotOptions): {
  snapshot: CustomResource;
  dataSource: { kind: string; name: Output<string>; apiGroup: string };
} {
  const {
    resourceName,
    snapshotName,
    namespace,
    pvcName,
    volumeSnapshotClassName = 'dev-vsc',
  } = options;

  const snapshot = new CustomResource(resourceName, {
    apiVersion: 'snapshot.storage.k8s.io/v1',
    kind: 'VolumeSnapshot',
    metadata: {
      name: snapshotName,
      namespace: namespace,
    },
    spec: {
      volumeSnapshotClassName: volumeSnapshotClassName,
      source: {
        persistentVolumeClaimName: pvcName,
      },
    },
  });

  const dataSource = {
    kind: 'VolumeSnapshot',
    name: snapshot.metadata.name,
    apiGroup: 'snapshot.storage.k8s.io',
  };

  return { snapshot, dataSource };
}
