// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as gcp from '@pulumi/gcp';
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import { spliceEnvConfig } from '@lfdecentralizedtrust/splice-pulumi-common/src/config/envConfig';

export function createCachePvc(
  runnersNamespace: k8s.core.v1.Namespace,
  cachePvcName: string
): k8s.core.v1.PersistentVolumeClaim {
  // A filestore for the cache drives that are mounted directly to the runners
  // filestore minimum capacity to provision an ssd instance is 2.5TB
  const capacityGb = 2560;
  const filestore = new gcp.filestore.Instance(`gha-filestore`, {
    tier: 'BASIC_SSD',
    fileShares: {
      name: 'gha_share',
      capacityGb: capacityGb,
    },
    networks: [
      {
        network: 'default',
        modes: ['MODE_IPV4'],
      },
    ],
    location: spliceEnvConfig.requireEnv('DB_CLOUDSDK_COMPUTE_ZONE'),
  });
  const filestoreIpAddress = filestore.networks[0].ipAddresses[0];
  const persistentVolume = new k8s.core.v1.PersistentVolume('gha-cache-pv', {
    metadata: {
      name: 'gha-cache-pv',
      namespace: runnersNamespace.metadata.name,
    },
    spec: {
      capacity: {
        storage: `${capacityGb}Gi`,
      },
      accessModes: ['ReadWriteMany'],
      persistentVolumeReclaimPolicy: 'Retain',
      storageClassName: '',
      csi: {
        driver: 'filestore.csi.storage.gke.io',
        volumeHandle: pulumi.interpolate`modeInstance/${filestore.location}/${filestore.name}/${filestore.fileShares.name}`,
        volumeAttributes: {
          ip: filestoreIpAddress,
          volume: filestore.fileShares.name,
        },
      },
    },
  });
  const cachePvc = new k8s.core.v1.PersistentVolumeClaim(cachePvcName, {
    metadata: {
      name: cachePvcName,
      namespace: runnersNamespace.metadata.name,
    },
    spec: {
      volumeName: persistentVolume.metadata.name,
      accessModes: ['ReadWriteMany'],
      storageClassName: '',
      resources: {
        requests: {
          storage: `${capacityGb}Gi`,
        },
      },
    },
  });

  return cachePvc;
}
