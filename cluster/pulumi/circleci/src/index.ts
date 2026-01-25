// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as gcp from '@pulumi/gcp';
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import {
  appsAffinityAndTolerations,
  ChartValues,
  HELM_MAX_HISTORY_SIZE,
  infraAffinityAndTolerations,
} from '@lfdecentralizedtrust/splice-pulumi-common';
import { spliceEnvConfig } from '@lfdecentralizedtrust/splice-pulumi-common/src/config/envConfig';
import { Namespace } from '@pulumi/kubernetes/core/v1';

const circleCiNamespace = new Namespace('circleci-runner', {
  metadata: {
    name: 'circleci-runner',
  },
});

// Below logic assumes that the cluster has workload identity enabled.
// You can enforce this via `cncluster cluster_enable_workload_identity`.

// Create a GCP SA for CCI (CCI SA) and a k8s service account (CCI KSA) that is connected to the GCP SA.
const cciGcpServiceAccount = new gcp.serviceaccount.Account('cci-deployments-gcp-sa', {
  accountId: 'cci-deployments-gcp-sa',
  displayName: 'CircleCI GCP Service Account for Cluster Deployments',
  description: 'Service account for CircleCI to access GCP resources.',
});
const cciK8sServiceAccount = new k8s.core.v1.ServiceAccount('cci-deployments-k8s-sa', {
  metadata: {
    name: 'cci-deployments-k8s-sa',
    namespace: circleCiNamespace.metadata.name,
    annotations: {
      'iam.gke.io/gcp-service-account': cciGcpServiceAccount.email,
    },
  },
});
// Grant the CCI GCP SA the necessary roles to use use workload identity via the CCI KSA.
new gcp.projects.IAMMember('cci-gcp-service-account-workload-identity-role', {
  project: cciGcpServiceAccount.project,
  member: pulumi.interpolate`serviceAccount:${cciGcpServiceAccount.project}.svc.id.goog[${circleCiNamespace.metadata.name}/${cciK8sServiceAccount.metadata.name}]`,
  role: 'roles/iam.workloadIdentityUser',
});
// Grant the CCI GCP SA the necessary roles to access GCP resources.
// => via infra Pulumi project as it affects other GCP projects

// filestore minimum capacity to provision a hdd instance is 1TB
const capacityGb = 1024;
const filestore = new gcp.filestore.Instance(`cci-filestore`, {
  tier: 'BASIC_HDD',
  fileShares: {
    name: 'cci_share',
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
const persistentVolume = new k8s.core.v1.PersistentVolume('cci-cache-pv', {
  metadata: {
    name: 'cci-cache-pv',
    namespace: circleCiNamespace.metadata.name,
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

const cachePvc = 'cci-cache-pvc';
const persistentVolumeClaim = new k8s.core.v1.PersistentVolumeClaim(cachePvc, {
  metadata: {
    name: cachePvc,
    namespace: circleCiNamespace.metadata.name,
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

function resourceClass(
  tokenSecretName: string,
  resources: k8s.types.input.core.v1.ResourceRequirements,
  k8sServiceAccountName?: pulumi.Output<string>
): ChartValues {
  // Read token from gcp secret manager
  const token = gcp.secretmanager.getSecretVersionOutput({
    secret: tokenSecretName,
  }).secretData;
  return {
    token: token,
    metadata: {
      // prevent eviction by the gke autoscaler
      annotations: {
        'cluster-autoscaler.kubernetes.io/safe-to-evict': 'false',
      },
    },
    spec: {
      serviceAccountName: k8sServiceAccountName,
      containers: [
        {
          resources: resources,
          // required to mount the nix store inside the container from the NFS
          securityContext: {
            privileged: true,
          },
          volumeMounts: [
            {
              name: 'cache',
              mountPath: '/cache',
            },
            {
              name: 'nix',
              mountPath: '/nix',
            },
          ],
        },
      ],
      volumes: [
        {
          name: 'cache',
          persistentVolumeClaim: {
            claimName: persistentVolumeClaim.metadata.name,
          },
        },
        {
          name: 'nix',
          ephemeral: {
            volumeClaimTemplate: {
              spec: {
                accessModes: ['ReadWriteOnce'],
                // only hyperdisks are supported on c4 nodes
                storageClassName: 'hyperdisk-balanced-rwo',
                resources: {
                  requests: {
                    storage: '48Gi',
                  },
                },
              },
            },
          },
        },
      ],
      ...appsAffinityAndTolerations,
    },
  };
}

new k8s.helm.v3.Release('container-agent', {
  name: 'container-agent',
  chart: 'container-agent',
  version: '101.1.3',
  namespace: circleCiNamespace.metadata.name,
  repositoryOpts: {
    repo: 'https://packagecloud.io/circleci/container-agent/helm',
  },
  values: {
    agent: {
      replicaCount: 3,
      maxConcurrentTasks: 100,
      resourceClasses: {
        'dach_ny/cn-runner-for-deployments': resourceClass(
          'circleci_runner_token_for-deployments',
          {
            requests: {
              cpu: '2',
              memory: '8Gi',
            },
          },
          // distingushing feature: runs as the special service account we created
          cciK8sServiceAccount.metadata.name
        ),
        'dach_ny/cn-runner-for-testing': resourceClass('circleci_runner_token_for-testing', {
          requests: {
            cpu: '2',
            memory: '8Gi',
          },
        }),
        'dach_ny/cn-runner-large': resourceClass('circleci_runner_token_large', {
          requests: {
            cpu: '5',
            memory: '24Gi',
          },
          limits: {
            memory: '40Gi', // the high resource tests really use lots all of this
          },
        }),
      },
      terminationGracePeriodSeconds: 18300, // 5h5m
      maxRunTime: '5h',
      maxConcurrentTask: 50,
      kubeGCThreshold: '5h5m',
      resources: {
        requests: {
          cpu: '1',
          memory: '512Mi',
        },
      },
      ...infraAffinityAndTolerations,
      maxHistory: HELM_MAX_HISTORY_SIZE,
    },
  },
});
