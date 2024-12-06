import * as k8s from '@pulumi/kubernetes';
import { ConfigMap, Namespace, PersistentVolumeClaim, Secret } from '@pulumi/kubernetes/core/v1';
import { Release } from '@pulumi/kubernetes/helm/v3';
import { Role } from '@pulumi/kubernetes/rbac/v1';
import { Resource } from '@pulumi/pulumi';
import {
  appsAffinityAndTolerations,
  HELM_MAX_HISTORY_SIZE,
  imagePullSecretByNamespaceNameForServiceAccount,
  infraAffinityAndTolerations,
} from 'splice-pulumi-common';
import { ArtifactoryCreds } from 'splice-pulumi-common/src/artifactory';
import { spliceEnvConfig } from 'splice-pulumi-common/src/config/envConfig';
import yaml from 'yaml';

import { createCachePvc } from './cache';

type ResourcesSpec = {
  requests?: {
    cpu?: string;
    memory?: string;
  };
  limits?: {
    cpu?: string;
    memory?: string;
  };
};

function installDockerRunnerScaleSet(
  name: string,
  runnersNamespace: Namespace,
  tokenSecret: Secret,
  cachePvc: PersistentVolumeClaim,
  configMap: ConfigMap,
  dockerConfigSecret: Secret,
  resources: ResourcesSpec,
  dependsOn: Resource[]
): k8s.helm.v3.Release {
  return new k8s.helm.v3.Release(
    name,
    {
      chart: 'oci://ghcr.io/actions/actions-runner-controller-charts/gha-runner-scale-set',
      version: '0.9.3',
      namespace: runnersNamespace.metadata.name,
      values: {
        githubConfigUrl: 'https://github.com/DACH-NY/canton-network-node',
        githubConfigSecret: tokenSecret.metadata.name,
        runnerScaleSetName: name,
        listenerTemplate: {
          spec: {
            containers: [{ name: 'listener' }],
            ...infraAffinityAndTolerations,
          },
        },
        template: {
          spec: {
            metadata: {
              // prevent eviction by the gke autoscaler
              annotations: {
                'cluster-autoscaler.kubernetes.io/safe-to-evict': 'false',
              },
            },
            initContainers: [
              {
                name: 'init-dind-externals',
                image: 'ghcr.io/actions/actions-runner:latest',
                command: ['cp', '-r', '-v', '/home/runner/externals/.', '/home/runner/tmpDir/'],
                volumeMounts: [
                  {
                    name: 'dind-externals',
                    mountPath: '/home/runner/tmpDir',
                  },
                ],
              },
            ],
            containers: [
              {
                name: 'runner',
                image: 'ghcr.io/actions/actions-runner:latest',
                command: ['/home/runner/run.sh'],
                env: [
                  {
                    name: 'DOCKER_HOST',
                    value: 'unix:///var/run/docker.sock',
                  },
                ],
                resources,
                volumeMounts: [
                  {
                    name: 'work',
                    mountPath: '/home/runner/_work',
                  },
                  {
                    name: 'dind-sock',
                    mountPath: '/var/run',
                  },
                  {
                    name: 'docker-config',
                    mountPath: '/home/runner/.docker/config.json',
                    readOnly: true,
                    subPath: 'config.json',
                  },
                ],
              },
              {
                name: 'dind',
                image: 'docker:dind',
                args: [
                  'dockerd',
                  '--host=unix:///var/run/docker.sock',
                  '--group=$(DOCKER_GROUP_GID)',
                ],
                env: [
                  {
                    name: 'DOCKER_GROUP_GID',
                    value: '123',
                  },
                ],
                resources,
                securityContext: {
                  privileged: true,
                },
                volumeMounts: [
                  {
                    name: 'work',
                    mountPath: '/home/runner/_work',
                  },
                  {
                    name: 'dind-sock',
                    mountPath: '/var/run',
                  },
                  {
                    name: 'dind-externals',
                    mountPath: '/home/runner/externals',
                  },
                  {
                    name: 'cache',
                    mountPath: '/cache',
                  },
                  {
                    name: 'daemon-json',
                    mountPath: '/etc/docker/daemon.json',
                    readOnly: true,
                    subPath: 'daemon.json',
                  },
                ],
              },
            ],
            volumes: [
              {
                name: 'work',
                emptyDir: {},
              },
              {
                name: 'dind-sock',
                emptyDir: {},
              },
              {
                name: 'dind-externals',
                emptyDir: {},
              },
              {
                name: 'cache',
                persistentVolumeClaim: {
                  claimName: cachePvc.metadata.name,
                },
              },
              {
                name: 'daemon-json',
                configMap: {
                  name: configMap.metadata.name,
                },
              },
              {
                name: 'docker-config',
                secret: {
                  secretName: dockerConfigSecret.metadata.name,
                },
              },
            ],
            ...appsAffinityAndTolerations,
          },
        },
        ...infraAffinityAndTolerations,
        maxHistory: HELM_MAX_HISTORY_SIZE,
      },
    },
    {
      dependsOn: dependsOn,
    }
  );

  // imagePullSecretByNamespaceName(runnersNamespace.metadata.name, )
}

function installDockerRunnerScaleSets(
  controller: k8s.helm.v3.Release,
  runnersNamespace: Namespace,
  tokenSecret: Secret,
  cachePvc: PersistentVolumeClaim
): void {
  // The internal DiD network is not working with the default MTU of 1500, we need to set it lower.
  // The solution is borrowed from https://github.com/actions/actions-runner-controller/discussions/2993
  const configMap = new k8s.core.v1.ConfigMap(
    'gha-runner-config',
    {
      metadata: {
        name: 'gha-runner-config',
        namespace: runnersNamespace.metadata.name,
      },
      data: {
        'daemon.json': JSON.stringify({
          mtu: 1400,
          'default-network-opts': {
            bridge: {
              'com.docker.network.driver.mtu': '1400',
            },
          },
        }),
      },
    },
    {
      dependsOn: runnersNamespace,
    }
  );
  const artifactoryCreds = ArtifactoryCreds.getCreds().creds;
  const configJsonBas64 = artifactoryCreds.apply(keys => {
    const creds = `${keys.username}:${keys.password}`;
    const artifactoryCredsBase64 = Buffer.from(creds).toString('base64');
    return Buffer.from(
      JSON.stringify({
        auths: {
          'digitalasset-canton-network-docker.jfrog.io': {
            auth: artifactoryCredsBase64,
          },
          'digitalasset-canton-network-docker-dev.jfrog.io': {
            auth: artifactoryCredsBase64,
          },
        },
      })
    ).toString('base64');
  });
  const dockerConfigSecret = new k8s.core.v1.Secret('docker-config-secret', {
    metadata: {
      namespace: runnersNamespace.metadata.name,
    },
    data: {
      'config.json': configJsonBas64,
    },
  });

  const dependsOn = [tokenSecret, controller, configMap, cachePvc, dockerConfigSecret];

  installDockerRunnerScaleSet(
    'self-hosted-docker-tiny',
    runnersNamespace,
    tokenSecret,
    cachePvc,
    configMap,
    dockerConfigSecret,
    {
      requests: {
        cpu: '0.1',
        memory: '256Mi',
      },
    },
    [...dependsOn, tokenSecret, controller, configMap, cachePvc, dockerConfigSecret]
  );

  installDockerRunnerScaleSet(
    'self-hosted-docker',
    runnersNamespace,
    tokenSecret,
    cachePvc,
    configMap,
    dockerConfigSecret,
    {
      requests: {
        // TODO(#15988) This is smaller than on CCI runners, but seems to suffice at least for now
        cpu: '1',
        memory: '4Gi',
      },
    },
    [...dependsOn, tokenSecret, controller, configMap, cachePvc, dockerConfigSecret]
  );

  installDockerRunnerScaleSet(
    'self-hosted-docker-large',
    runnersNamespace,
    tokenSecret,
    cachePvc,
    configMap,
    dockerConfigSecret,
    {
      requests: {
        cpu: '5',
        memory: '24Gi',
      },
      limits: {
        cpu: '6',
        memory: '40Gi', // the high resource tests really use lots all of this
      },
    },
    dependsOn
  );
}

function installK8sRunnerScaleSet(
  runnersNamespace: Namespace,
  name: string,
  tokenSecret: Secret,
  cachePvcName: string,
  resources: ResourcesSpec,
  serviceAccountName: string,
  dependsOn: Resource[]
): Release {
  const podConfigMapName = `${name}-pod-config`;
  // A configMap that will be mounted to runner pods and provide additional pod spec for the workflow pods
  const workflowPodConfigMap = new k8s.core.v1.ConfigMap(
    podConfigMapName,
    {
      metadata: {
        name: podConfigMapName,
        namespace: runnersNamespace.metadata.name,
      },
      data: {
        'pod.yaml': yaml.stringify({
          spec: {
            volumes: [
              {
                name: 'cache',
                persistentVolumeClaim: {
                  claimName: cachePvcName,
                },
              },
            ],
            containers: [
              {
                name: '$job',
                volumeMounts: [
                  {
                    name: 'cache',
                    mountPath: '/cache',
                  },
                ],
                // required to mount the nix store inside the container from the NFS
                securityContext: {
                  privileged: true,
                },
                resources,
              },
            ],
            serviceAccountName: serviceAccountName,
          },
        }),
      },
    },
    {
      dependsOn: runnersNamespace,
    }
  );

  return new k8s.helm.v3.Release(
    name,
    {
      chart: 'oci://ghcr.io/actions/actions-runner-controller-charts/gha-runner-scale-set',
      version: '0.9.3',
      namespace: runnersNamespace.metadata.name,
      values: {
        githubConfigUrl: 'https://github.com/DACH-NY/canton-network-node',
        githubConfigSecret: tokenSecret.metadata.name,
        runnerScaleSetName: name,
        listenerTemplate: {
          spec: {
            containers: [{ name: 'listener' }],
            ...infraAffinityAndTolerations,
          },
        },
        template: {
          spec: {
            metadata: {
              // prevent eviction by the gke autoscaler
              annotations: {
                'cluster-autoscaler.kubernetes.io/safe-to-evict': 'false',
              },
            },
            containers: [
              {
                name: 'runner',
                image: 'ghcr.io/actions/actions-runner:latest',
                imagePullPolicy: 'Always',
                command: ['/home/runner/run.sh'],
                env: [
                  {
                    name: 'ACTIONS_RUNNER_CONTAINER_HOOKS',
                    value: '/home/runner/k8s/index.js',
                  },
                  {
                    name: 'ACTIONS_RUNNER_POD_NAME',
                    valueFrom: {
                      fieldRef: {
                        fieldPath: 'metadata.name',
                      },
                    },
                  },
                  {
                    name: 'ACTIONS_RUNNER_REQUIRE_JOB_CONTAINER',
                    value: 'true',
                  },
                  {
                    // Instruct the container-hook to apply the extra spec parameters to the workflow pod
                    name: 'ACTIONS_RUNNER_CONTAINER_HOOK_TEMPLATE',
                    value: '/pod.yaml',
                  },
                ],
                volumeMounts: [
                  {
                    name: 'work',
                    mountPath: '/home/runner/_work',
                  },
                  {
                    name: 'workflow-pod-config',
                    mountPath: '/pod.yaml',
                    readOnly: true,
                    subPath: 'pod.yaml',
                  },
                ],
                resources: {
                  // These are resources for the runner pod itself, not the workflow ones.
                  requests: {
                    cpu: '0.1',
                    memory: '2Gi',
                  },
                },
              },
            ],
            securityContext: {
              // Mount the volumes as owned by the runner user
              fsGroup: 1001,
            },
            ...appsAffinityAndTolerations,
            volumes: [
              {
                name: 'work',
                ephemeral: {
                  volumeClaimTemplate: {
                    spec: {
                      accessModes: ['ReadWriteOnce'],
                      storageClassName: 'standard-rwo',
                      resources: {
                        requests: {
                          storage: '16Gi',
                        },
                      },
                    },
                  },
                },
              },
              {
                name: 'workflow-pod-config',
                configMap: {
                  name: podConfigMapName,
                },
              },
            ],
            serviceAccountName: serviceAccountName,
          },
        },
        ...infraAffinityAndTolerations,
        maxHistory: HELM_MAX_HISTORY_SIZE,
        controllerServiceAccount: {
          namespace: 'gha-runner-controller',
          name: 'gha-runner-scale-set-controller-9a0b4f49-gha-rs-controller',
        },
      },
    },
    {
      dependsOn: [...dependsOn, workflowPodConfigMap],
    }
  );
}

function installK8sRunnersServiceAccount(runnersNamespace: Namespace, name: string) {
  // If we leave it to the runners Helm charts to create the service account,
  // it does not allow adding an image pull secret to the service account (and it creates
  // it with un unpredictable name, so also not easy to patch it after-the-fact). We therefore
  // create it ourselves with the necessary permissions and the image pull secret.
  const sa = new k8s.core.v1.ServiceAccount(
    name,
    {
      metadata: {
        name: name,
        namespace: runnersNamespace.metadata.name,
      },
    },
    {
      dependsOn: runnersNamespace,
    }
  );
  const role = new Role(name, {
    metadata: {
      name: name,
      namespace: runnersNamespace.metadata.name,
    },
    rules: [
      {
        apiGroups: [''],
        resources: ['pods'],
        verbs: ['create', 'get', 'list', 'delete'],
      },
      {
        apiGroups: [''],
        resources: ['pods/exec'],
        verbs: ['create', 'get'],
      },
      {
        apiGroups: [''],
        resources: ['pods/log'],
        verbs: ['list', 'get', 'watch'],
      },
      {
        apiGroups: ['batch'],
        resources: ['jobs'],
        verbs: ['get', 'list', 'create', 'delete'],
      },
      {
        apiGroups: [''],
        resources: ['secrets'],
        verbs: ['get', 'list', 'create', 'delete'],
      },
    ],
  });
  new k8s.rbac.v1.RoleBinding(
    name,
    {
      metadata: {
        name: name,
        namespace: runnersNamespace.metadata.name,
      },
      roleRef: {
        apiGroup: 'rbac.authorization.k8s.io',
        kind: 'Role',
        name: role.metadata.name,
      },
      subjects: [
        {
          kind: 'ServiceAccount',
          name: sa.metadata.name,
          namespace: sa.metadata.namespace,
        },
      ],
    },
    {
      dependsOn: [sa, role],
    }
  );

  imagePullSecretByNamespaceNameForServiceAccount('gha-runners', name, [sa]);
}

function installK8sRunnerScaleSets(
  controller: k8s.helm.v3.Release,
  runnersNamespace: Namespace,
  tokenSecret: Secret,
  cachePvcName: string
): void {
  const dependsOn = [controller, runnersNamespace, tokenSecret];

  const saName = 'k8s-runners';
  installK8sRunnersServiceAccount(runnersNamespace, saName);

  installK8sRunnerScaleSet(
    runnersNamespace,
    'self-hosted-k8s',
    tokenSecret,
    cachePvcName,
    {
      requests: {
        // TODO(#15988) This is smaller than on CCI runners, but seems to suffice at least for now
        cpu: '1',
        memory: '4Gi',
      },
    },
    saName,
    dependsOn
  );
  installK8sRunnerScaleSet(
    runnersNamespace,
    'self-hosted-k8s-large',
    tokenSecret,
    cachePvcName,
    {
      requests: {
        cpu: '5',
        memory: '24Gi',
      },
      limits: {
        cpu: '6',
        memory: '40Gi', // the high resource tests really use lots all of this
      },
    },
    saName,
    dependsOn
  );
}

export function installRunnerScaleSets(controller: k8s.helm.v3.Release): void {
  const runnersNamespace = new Namespace('gha-runners', {
    metadata: {
      name: 'gha-runners',
    },
  });

  const tokenSecret = new k8s.core.v1.Secret(
    'gh-access-token',
    {
      metadata: {
        name: 'gh-access-token',
        namespace: runnersNamespace.metadata.name,
      },
      stringData: {
        // TODO(#15988): for now, this uses the token of the person running the pulumi command.
        // It should instead be canton-network-da's token, or that of some other service account.
        // (that didn't work originally, so worked around it with the personal token for now)
        github_token: spliceEnvConfig.requireEnv('GITHUB_TOKEN'),
      },
    },
    {
      dependsOn: runnersNamespace,
    }
  );
  const cachePvcName = 'gha-cache-pvc';
  const cachePvc = createCachePvc(runnersNamespace, cachePvcName);

  installDockerRunnerScaleSets(controller, runnersNamespace, tokenSecret, cachePvc);
  installK8sRunnerScaleSets(controller, runnersNamespace, tokenSecret, cachePvcName);
}
