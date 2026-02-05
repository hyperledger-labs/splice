// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';

export function installStorageClasses(): void {
  // Follows https://cloud.google.com/kubernetes-engine/docs/how-to/persistent-volumes/hyperdisk
  new k8s.storage.v1.StorageClass('hyperdisk-balanced-rwo', {
    metadata: {
      name: 'hyperdisk-balanced-rwo',
    },
    provisioner: 'pd.csi.storage.gke.io',
    volumeBindingMode: 'WaitForFirstConsumer',
    allowVolumeExpansion: true,
    parameters: {
      type: 'hyperdisk-balanced',
      'provisioned-throughput-on-create': '250Mi',
      'provisioned-iops-on-create': '7000',
    },
  });
  new k8s.storage.v1.StorageClass('hyperdisk-standard-rwo', {
    metadata: {
      name: 'hyperdisk-standard-rwo',
    },
    provisioner: 'pd.csi.storage.gke.io',
    volumeBindingMode: 'WaitForFirstConsumer',
    allowVolumeExpansion: true,
    parameters: {
      type: 'hyperdisk-balanced',
      // use default iops/throughput for lower cost
    },
  });
}
