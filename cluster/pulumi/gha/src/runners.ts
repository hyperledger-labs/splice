import * as k8s from '@pulumi/kubernetes';
import { Namespace } from '@pulumi/kubernetes/core/v1';
import {
  appsAffinityAndTolerations,
  HELM_MAX_HISTORY_SIZE,
  infraAffinityAndTolerations,
} from 'splice-pulumi-common';
import { spliceEnvConfig } from 'splice-pulumi-common/src/config/envConfig';

import { createCachePvc } from './cache';

export function installRunnerScaleSet(controller: k8s.helm.v3.Release): k8s.helm.v3.Release {
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
  const cachePvc = createCachePvc(runnersNamespace);
  // TODO(#15988): for now, this uses the token of the person running the pulumi command.
  // It should be a service account instead.
  const artifactoryCreds = `${spliceEnvConfig.requireEnv('ARTIFACTORY_USER')}:${spliceEnvConfig.requireEnv(`ARTIFACTORY_PASSWORD`)}`;
  const artifactoryCredsBase64 = Buffer.from(artifactoryCreds).toString('base64');
  const dockerConfigSecret = new k8s.core.v1.Secret('docker-config-secret', {
    metadata: {
      namespace: runnersNamespace.metadata.name,
    },
    data: {
      'config.json': Buffer.from(
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
      ).toString('base64'),
    },
  });

  return new k8s.helm.v3.Release(
    'gha-runner-scale-set',
    {
      chart: 'oci://ghcr.io/actions/actions-runner-controller-charts/gha-runner-scale-set',
      version: '0.9.3',
      namespace: runnersNamespace.metadata.name,
      values: {
        githubConfigUrl: 'https://github.com/DACH-NY/canton-network-node',
        githubConfigSecret: tokenSecret.metadata.name,
        runnerScaleSetName: 'self-hosted-docker',
        listenerTemplate: {
          spec: {
            containers: [{ name: 'listener' }],
            ...infraAffinityAndTolerations,
          },
        },
        template: {
          spec: {
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
      dependsOn: [tokenSecret, controller, configMap, cachePvc, dockerConfigSecret],
    }
  );
}
