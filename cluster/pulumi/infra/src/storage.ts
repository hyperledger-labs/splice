// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { CustomResource } from '@pulumi/kubernetes/apiextensions';

export function configureStorage(): void {
  // Install a VolumeSnapshotClass to be used for PVC snapshots
  new CustomResource('dev-vsc', {
    apiVersion: 'snapshot.storage.k8s.io/v1',
    kind: 'VolumeSnapshotClass',
    metadata: {
      name: 'dev-vsc',
    },
    driver: 'pd.csi.storage.gke.io',
    deletionPolicy: 'Delete',
  });
}
