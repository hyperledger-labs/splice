// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import {
  appsAffinityAndTolerations,
  DOCKER_REPO,
  ExactNamespace,
  HELM_MAX_HISTORY_SIZE,
  imagePullSecretByNamespaceNameForServiceAccount,
  infraAffinityAndTolerations,
} from '@lfdecentralizedtrust/splice-pulumi-common';
import { DockerConfig } from '@lfdecentralizedtrust/splice-pulumi-common/src/dockerConfig';
import { getSecretVersionOutput } from '@pulumi/gcp/secretmanager/getSecretVersion';
import { ConfigMap, Namespace, PersistentVolumeClaim, Secret } from '@pulumi/kubernetes/core/v1';
import { Release } from '@pulumi/kubernetes/helm/v3';
import { Role } from '@pulumi/kubernetes/rbac/v1';
import { Resource } from '@pulumi/pulumi';
import yaml from 'js-yaml';

import { createCachePvc } from './cache';
import { ghaConfig } from './config';
import { createCloudSQLInstanceForPerformanceTests, PerformanceTestDb } from './performanceTests';

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

const runnerSpecs = [
  {
    name: 'tiny',
    k8s: false,
    docker: true,
    resources: {
      requests: {
        cpu: '0.5',
        memory: '512Mi',
      },
      limits: {
        cpu: '0.5',
        memory: '512Mi',
      },
    },
  },
  {
    name: 'x-small',
    k8s: true,
    docker: false,
    resources: {
      requests: {
        cpu: '4',
        memory: '10Gi',
      },
      limits: {
        cpu: '6',
        memory: '10Gi',
      },
    },
  },
  {
    name: 'small',
    k8s: true,
    docker: false,
    resources: {
      requests: {
        cpu: '4',
        memory: '18Gi',
      },
      limits: {
        cpu: '6',
        memory: '18Gi',
      },
    },
  },
  {
    name: 'medium',
    k8s: true,
    docker: true,
    resources: {
      requests: {
        cpu: '5',
        memory: '24Gi',
      },
      limits: {
        cpu: '7',
        memory: '24Gi',
      },
    },
  },
  {
    name: 'large',
    k8s: true,
    docker: true,
    resources: {
      requests: {
        cpu: '6',
        memory: '32Gi',
      },
      limits: {
        cpu: '8',
        memory: '32Gi',
      },
    },
  },
  {
    name: 'x-large',
    k8s: true,
    docker: false,
    resources: {
      requests: {
        cpu: '8',
        memory: '52Gi',
      },
      limits: {
        cpu: '10',
        memory: '52Gi',
      },
    },
  },
];

const localnetHostAliases = [
  {
    ip: '127.0.0.1',
    hostnames: [
      // Used by the localnet tests
      'ans.localhost',
      'canton.localhost',
      'scan.localhost',
      'sv.localhost',
      'wallet.localhost',
      'json-ledger-api.localhost',
      'grpc-ledger-api.localhost',
    ],
  },
];

function installDockerRunnerScaleSet(
  name: string,
  runnersNamespace: Namespace,
  tokenSecret: Secret,
  cachePvc: PersistentVolumeClaim,
  configMap: ConfigMap,
  dockerConfigSecret: Secret,
  resources: ResourcesSpec,
  serviceAccountName: string,
  dependsOn: Resource[]
): k8s.helm.v3.Release {
  const repo = ghaConfig.githubRepo;
  return new k8s.helm.v3.Release(
    name,
    {
      chart: 'oci://ghcr.io/actions/actions-runner-controller-charts/gha-runner-scale-set',
      version: ghaConfig.runnerScaleSetVersion,
      namespace: runnersNamespace.metadata.name,
      values: {
        githubConfigUrl: repo,
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
            hostAliases: localnetHostAliases,
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
                image: `${DOCKER_REPO}/splice-test-docker-runner:${ghaConfig.runnerVersion}`,
                command: ['/home/runner/run.sh'],
                env: [
                  {
                    name: 'DOCKER_HOST',
                    value: 'unix:///var/run/docker.sock',
                  },
                  {
                    name: 'RUNNER_WAIT_FOR_DOCKER_IN_SECONDS',
                    value: '120',
                  },
                ],
                resources,
                // required to mount the nix store inside the container from the NFS
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
                    name: 'docker-client-config',
                    mountPath: '/home/runner/.docker/config.json',
                    readOnly: true,
                    subPath: 'config.json',
                  },
                  {
                    name: 'cache',
                    mountPath: '/cache',
                  },
                ],
                ports: [
                  {
                    name: 'metrics',
                    containerPort: 8000,
                    protocol: 'TCP',
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
                name: 'docker-client-config',
                secret: {
                  secretName: dockerConfigSecret.metadata.name,
                },
              },
            ],
            serviceAccountName: serviceAccountName,
            ...appsAffinityAndTolerations,
          },
          metadata: {
            // prevent eviction by the gke autoscaler
            annotations: {
              'cluster-autoscaler.kubernetes.io/safe-to-evict': 'false',
            },
            labels: {
              // We add a runner-pod label, so that we can easily select it for monitoring
              'runner-pod': 'true',
            },
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
}

function installDockerRunnerScaleSets(
  controller: k8s.helm.v3.Release,
  runnersNamespace: Namespace,
  tokenSecret: Secret,
  cachePvc: PersistentVolumeClaim,
  serviceAccountName: string
): void {
  const configMap = new k8s.core.v1.ConfigMap(
    'gha-runner-config',
    {
      metadata: {
        name: 'gha-runner-config',
        namespace: runnersNamespace.metadata.name,
      },
      data: {
        'daemon.json': JSON.stringify({
          // The internal docker in docker network is not working with the default MTU of 1500, we need to set it lower.
          // The solution is borrowed from https://github.com/actions/actions-runner-controller/discussions/2993
          mtu: 1400,
          'default-network-opts': {
            bridge: {
              'com.docker.network.driver.mtu': '1400',
            },
          },
          // enable containerd image store, to support multi-platform images (see https://docs.docker.com/desktop/containerd/)
          features: {
            'containerd-snapshotter': true,
          },
          'registry-mirrors': [
            'http://docker-registry-mirror.docker-mirror.svc.cluster.local:5000',
          ],
          'insecure-registries': ['docker-registry-mirror.docker-mirror.svc.cluster.local:5000'],
        }),
      },
    },
    {
      dependsOn: runnersNamespace,
    }
  );

  const dockerClientConfigSecret = DockerConfig.getConfig().createDockerClientConfigSecret(
    runnersNamespace.metadata.name
  );

  const dependsOn = [tokenSecret, controller, configMap, cachePvc, dockerClientConfigSecret];

  runnerSpecs
    .filter(spec => spec.docker)
    .forEach(spec => {
      installDockerRunnerScaleSet(
        `self-hosted-docker-${spec.name}`,
        runnersNamespace,
        tokenSecret,
        cachePvc,
        configMap,
        dockerClientConfigSecret,
        spec.resources,
        serviceAccountName,
        dependsOn
      );
    });
}

// A note about resources: We create two pods per workflow: the runner pod and the workflow pod.
// They have implicit affinity between them as they communicate via a shared local PVC.
// The runner starts first, so even though it is quite lightweight, it already pins the node
// on which both will run. We therefore set the resource requests of the runner pod to be the
// request we actually need for the workflow. The limits are set on the workflow pod, to actually
// have the higher bound on actual usage.
function installK8sRunnerScaleSet(
  runnersNamespace: Namespace,
  name: string,
  tokenSecret: Secret,
  cachePvcName: string,
  resources: ResourcesSpec,
  serviceAccountName: string,
  dependsOn: Resource[],
  performanceTestsDb: PerformanceTestDb
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
        'pod.yaml': yaml.dump({
          spec: {
            hostAliases: localnetHostAliases,
            volumes: [
              {
                name: 'cache',
                persistentVolumeClaim: {
                  claimName: cachePvcName,
                },
              },
              {
                name: 'logs',
                emptyDir: {},
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
                  {
                    name: 'logs',
                    mountPath: '/logs',
                  },
                ],
                // required to mount the nix store inside the container from the NFS
                securityContext: {
                  privileged: true,
                },
                resources: {
                  // See note above on resource requests and limits.
                  requests: {
                    // We set the requests to a tiny non-zero number, just to prevent k8s from
                    // using the limits as the requests values.
                    cpu: '1m',
                    memory: '1m',
                  },
                  limits: resources?.limits,
                },
                ports: [
                  {
                    name: 'metrics',
                    containerPort: 8000,
                    protocol: 'TCP',
                  },
                ],
                imagePullPolicy: 'Always',
              },
            ],
            serviceAccountName: serviceAccountName,
            ...appsAffinityAndTolerations,
          },
          metadata: {
            // prevent eviction by the gke autoscaler
            annotations: {
              'cluster-autoscaler.kubernetes.io/safe-to-evict': 'false',
            },
          },
        }),
      },
    },
    {
      dependsOn: runnersNamespace,
    }
  );

  const runnerImage = `${DOCKER_REPO}/splice-test-runner-hook:${ghaConfig.runnerHookVersion}`;

  const repo = ghaConfig.githubRepo;

  return new k8s.helm.v3.Release(
    name,
    {
      chart: 'oci://ghcr.io/actions/actions-runner-controller-charts/gha-runner-scale-set',
      version: ghaConfig.runnerScaleSetVersion,
      namespace: runnersNamespace.metadata.name,
      values: {
        githubConfigUrl: repo,
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
            containers: [
              {
                name: 'runner',
                image: runnerImage,
                imagePullPolicy: 'dirty'.indexOf(runnerImage) ? 'Always' : 'IfNotPresent',
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
                  {
                    name: 'PERFORMANCE_TESTS_DB_HOST',
                    value: performanceTestsDb.address,
                  },
                  {
                    name: 'PERFORMANCE_TESTS_DB_USER',
                    value: 'cnadmin',
                  },
                  {
                    name: 'PERFORMANCE_TESTS_DB_PASSWORD',
                    valueFrom: {
                      secretKeyRef: {
                        key: 'postgresPassword',
                        name: performanceTestsDb.secretName,
                      },
                    },
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
                  // See note above on resource requests and limits on why we set the requests
                  // on the runner pod.
                  requests: resources
                    ? resources.requests
                    : {
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
                      // only hyperdisks are supported on c4 nodes
                      storageClassName: 'hyperdisk-balanced-rwo',
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
          metadata: {
            // prevent eviction by the gke autoscaler
            annotations: {
              'cluster-autoscaler.kubernetes.io/safe-to-evict': 'false',
            },
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

function installRunnersServiceAccount(runnersNamespace: Namespace, name: string) {
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
        apiGroups: [''],
        resources: ['services'],
        verbs: ['create', 'get', 'list', 'delete'],
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
  cachePvcName: string,
  serviceAccountName: string,
  performanceTestsDb: PerformanceTestDb
): void {
  const dependsOn = [controller, runnersNamespace, tokenSecret, performanceTestsDb.db];

  runnerSpecs
    .filter(spec => spec.k8s)
    .forEach(spec => {
      installK8sRunnerScaleSet(
        runnersNamespace,
        `self-hosted-k8s-${spec.name}`,
        tokenSecret,
        cachePvcName,
        spec.resources,
        serviceAccountName,
        dependsOn,
        performanceTestsDb
      );
    });
}

function installPodMonitor(runnersNamespace: Namespace) {
  // Define a PodMonitor to scrape metrics from the workflow runner pods
  // (identified by the presence of the 'runner-pod' label).
  return new k8s.apiextensions.CustomResource(
    'workflow-runner-pod-monitor',
    {
      apiVersion: 'monitoring.coreos.com/v1',
      kind: 'PodMonitor',
      metadata: {
        namespace: runnersNamespace.metadata.name,
        labels: { release: 'prometheus-grafana-monitoring' },
      },
      spec: {
        selector: {
          matchExpressions: [
            {
              key: 'runner-pod',
              operator: 'Exists',
            },
          ],
        },
        podMetricsEndpoints: [
          {
            port: 'metrics',
            interval: '28s',
            path: '/',
          },
        ],
      },
    },
    { dependsOn: runnersNamespace }
  );
}

const GHA_NAMESPACE_NAME = 'gha-runners';
export function installRunnerScaleSets(controller: k8s.helm.v3.Release): void {
  const runnersNamespace = new Namespace(GHA_NAMESPACE_NAME, {
    metadata: {
      name: GHA_NAMESPACE_NAME,
    },
  });
  const exactNs: ExactNamespace = {
    ns: runnersNamespace,
    logicalName: GHA_NAMESPACE_NAME,
  };

  const tokenSecret = new k8s.core.v1.Secret(
    'gh-access-token',
    {
      metadata: {
        name: 'gh-access-token',
        namespace: runnersNamespace.metadata.name,
      },
      stringData: {
        // This is the 'Self-hosted runner token' token for canton-network-da GH user.
        // Note that the user needs "read/write rights access to administration" and "read access to metadata"
        // (fine-grained) permissions for this to work, since the controller and
        // listeners use the actions/runners/registration-token endpoint to create a temporary token
        // for registration, and this endpoint seems to require admin rights.
        // TODO(DACH-NY/canton-network-node#17842): The recommended thing to do is use a GitHub App. See here for a guide
        // on setting it up: https://medium.com/@timburkhardt8/registering-github-self-hosted-runners-using-github-app-9cc952ea6ca
        github_token: getSecretVersionOutput({ secret: 'gh-runners-access-token' }).apply(
          k => k.secretData
        ),
      },
    },
    {
      dependsOn: runnersNamespace,
    }
  );
  const cachePvcName = 'gha-cache-pvc';
  const cachePvc = createCachePvc(runnersNamespace, cachePvcName);

  const saName = 'k8s-runners';
  installRunnersServiceAccount(runnersNamespace, saName);

  const performanceTestsDb = createCloudSQLInstanceForPerformanceTests(exactNs);
  installDockerRunnerScaleSets(controller, runnersNamespace, tokenSecret, cachePvc, saName);
  installK8sRunnerScaleSets(
    controller,
    runnersNamespace,
    tokenSecret,
    cachePvcName,
    saName,
    performanceTestsDb
  );
  installPodMonitor(runnersNamespace);
}
