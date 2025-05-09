import * as gcp from '@pulumi/gcp';
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import { Namespace } from '@pulumi/kubernetes/core/v1';
import {
  appsAffinityAndTolerations,
  HELM_MAX_HISTORY_SIZE,
  infraAffinityAndTolerations,
} from 'splice-pulumi-common';
import { spliceEnvConfig } from 'splice-pulumi-common/src/config/envConfig';

const circleCiNamespace = new Namespace('circleci-runner', {
  metadata: {
    name: 'circleci-runner',
  },
});
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

new k8s.helm.v3.Release('container-agent', {
  name: 'container-agent',
  chart: 'container-agent',
  version: '101.1.1',
  namespace: circleCiNamespace.metadata.name,
  repositoryOpts: {
    repo: 'https://packagecloud.io/circleci/container-agent/helm',
  },
  values: {
    agent: {
      replicaCount: 3,
      maxConcurrentTasks: 100,
      resourceClasses: {
        'dach_ny/cn-runner-for-testing': {
          token: spliceEnvConfig.requireEnv('SPLICE_PULUMI_CCI_RUNNER_TOKEN'),
          metadata: {
            // prevent eviction by the gke autoscaler
            annotations: {
              'cluster-autoscaler.kubernetes.io/safe-to-evict': 'false',
            },
          },
          spec: {
            containers: [
              {
                resources: {
                  requests: {
                    cpu: '2',
                    memory: '8Gi',
                  },
                },
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
                          storage: '24Gi',
                        },
                      },
                    },
                  },
                },
              },
            ],
            ...appsAffinityAndTolerations,
          },
        },
        'dach_ny/cn-runner-large': {
          token: spliceEnvConfig.requireEnv('SPLICE_PULUMI_CCI_RUNNER_LARGE_TOKEN'),
          metadata: {
            // prevent eviction by the gke autoscaler
            annotations: {
              'cluster-autoscaler.kubernetes.io/safe-to-evict': 'false',
            },
          },
          spec: {
            containers: [
              {
                resources: {
                  requests: {
                    cpu: '5',
                    memory: '24Gi',
                  },
                  limits: {
                    memory: '40Gi', // the high resource tests really use lots all of this
                  },
                },
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
                          storage: '24Gi',
                        },
                      },
                    },
                  },
                },
              },
            ],
            ...appsAffinityAndTolerations,
          },
        },
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
