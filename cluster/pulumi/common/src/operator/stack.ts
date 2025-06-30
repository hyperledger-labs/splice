// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import * as semver from 'semver';
import { Secret } from '@pulumi/kubernetes/core/v1';
import { Resource } from '@pulumi/pulumi';
import {
  CLUSTER_BASENAME,
  config,
  infraAffinityAndTolerations,
  isMainNet,
} from 'splice-pulumi-common';

import { spliceEnvConfig } from '../config/envConfig';
import { operatorDeploymentConfig } from './config';
import { GitFluxRef } from './flux-source';

export type EnvRefs = { [key: string]: unknown };

export function createEnvRefs(envSecretName: string, namespaceName: string): EnvRefs {
  const requiredEnvs = Array.from([
    'AUTH0_CN_MANAGEMENT_API_CLIENT_ID',
    'AUTH0_CN_MANAGEMENT_API_CLIENT_SECRET',
    'AUTH0_SV_MANAGEMENT_API_CLIENT_ID',
    'AUTH0_SV_MANAGEMENT_API_CLIENT_SECRET',
    'AUTH0_VALIDATOR_MANAGEMENT_API_CLIENT_ID',
    'AUTH0_VALIDATOR_MANAGEMENT_API_CLIENT_SECRET',
  ]);

  const optionalEnvs = Array.from([
    'K6_USERS_PASSWORD',
    'K6_VALIDATOR_ADMIN_PASSWORD',
    'SLACK_ACCESS_TOKEN',
  ]).concat(
    isMainNet
      ? ['AUTH0_MAIN_MANAGEMENT_API_CLIENT_SECRET', 'AUTH0_MAIN_MANAGEMENT_API_CLIENT_ID']
      : []
  );

  const env: {
    [key: string]: string;
  } = {};

  requiredEnvs.forEach(key => (env[key] = config.requireEnv(key)));
  optionalEnvs.forEach(key => {
    const optionalEnv = config.optionalEnv(key);
    if (optionalEnv) {
      env[key] = optionalEnv;
    }
  });

  const envSecret = new k8s.core.v1.Secret(envSecretName, {
    metadata: {
      name: envSecretName,
      namespace: namespaceName,
    },
    type: 'Opaque',
    stringData: env,
  });

  const envRefs: EnvRefs = {};
  Object.keys(env).forEach(key => {
    envRefs[key] = {
      type: 'Secret',
      secret: {
        name: envSecret.metadata.name,
        key: key,
      },
    };
  });
  return envRefs;
}

/*https://github.com/pulumi/pulumi-kubernetes-operator/blob/master/docs/stacks.md*/
export function createStackCR(
  name: string,
  projectName: string,
  namespaceName: string,
  supportsResetOnSameCommit: boolean,
  ref: GitFluxRef,
  envRefs: EnvRefs,
  gcpSecret: k8s.core.v1.Secret,
  extraEnvs: { [key: string]: string } = {},
  dependsOn: pulumi.Resource[] = []
): pulumi.CustomResource {
  if (operatorDeploymentConfig.useOperatorV2) {
    return createStackCRV2(
      name,
      namespaceName,
      ref,
      projectName,
      envRefs,
      extraEnvs,
      gcpSecret,
      supportsResetOnSameCommit,
      dependsOn
    );
  } else {
    return createStackCRV1(
      name,
      projectName,
      supportsResetOnSameCommit,
      ref,
      envRefs,
      extraEnvs,
      namespaceName,
      dependsOn
    );
  }
}

/*https://github.com/pulumi/pulumi-kubernetes-operator/blob/master/docs/stacks.md*/
export function createStackCRV1(
  name: string,
  projectName: string,
  supportsResetOnSameCommit: boolean,
  ref: GitFluxRef,
  envRefs: EnvRefs,
  extraEnvs: { [key: string]: string } = {},
  namespaceName: string = 'operator',
  dependsOn: pulumi.Resource[] = []
): pulumi.CustomResource {
  const privateConfigs = ref.config.privateConfigsDir
    ? {
        PRIVATE_CONFIGS_PATH: {
          type: 'Literal',
          literal: {
            value: `/tmp/pulumi-working/operator/${name}/workspace/${ref.config.privateConfigsDir}`,
          },
        },
      }
    : {};
  const publicConfigs = ref.config.publicConfigsDir
    ? {
        PUBLIC_CONFIGS_PATH: {
          type: 'Literal',
          literal: {
            value: `/tmp/pulumi-working/operator/${name}/workspace/${ref.config.publicConfigsDir}`,
          },
        },
      }
    : {};
  return new k8s.apiextensions.CustomResource(
    name,
    {
      apiVersion: 'pulumi.com/v1',
      kind: 'Stack',
      metadata: { name: name, namespace: namespaceName },
      spec: {
        ...{
          stack: `organization/${projectName}/${name}.${CLUSTER_BASENAME}`,
          backend: config.requireEnv('PULUMI_BACKEND_URL'),
          envRefs: {
            ...envRefs,
            SPLICE_ROOT: {
              type: 'Literal',
              literal: {
                value: `/tmp/pulumi-working/operator/${name}/workspace/${ref.config.spliceRoot}`,
              },
            },
            DEPLOYMENT_DIR: {
              type: 'Literal',
              literal: {
                value: `/tmp/pulumi-working/operator/${name}/workspace/${ref.config.deploymentDir}`,
              },
            },
            ...privateConfigs,
            ...publicConfigs,
            GCP_CLUSTER_BASENAME: {
              type: 'Literal',
              literal: {
                value: CLUSTER_BASENAME,
              },
            },
            ...Object.keys(extraEnvs).reduce<{
              [key: string]: unknown;
            }>((acc, key) => {
              acc[key] = {
                type: 'Literal',
                literal: {
                  value: extraEnvs[key],
                },
              };
              return acc;
            }, {}),
          },
          fluxSource: {
            sourceRef: {
              apiVersion: ref.resource.apiVersion,
              kind: ref.resource.kind,
              name: ref.resource.metadata.name,
            },
            dir: `${ref.config.pulumiBaseDir}/${projectName}`,
          },
          // Do not resync the stack when the commit hash matches the last one
          continueResyncOnCommitMatch: false,
          destroyOnFinalize: false,
          // Enforce that the stack already exists
          useLocalStackOnly: true,
          // retry if the stack is locked by another operation
          retryOnUpdateConflict: true,
        },
        ...(supportsResetOnSameCommit
          ? {
              continueResyncOnCommitMatch: true,
              resyncFrequencySeconds: 300,
              // TODO(#924): consider scaling down the operator instead
              refresh: true,
            }
          : {}),
      },
    },
    {
      dependsOn: dependsOn,
    }
  );
}

function createStackCRV2(
  name: string,
  namespaceName: string,
  ref: GitFluxRef,
  projectName: string,
  envRefs: EnvRefs,
  extraEnvs: {
    [p: string]: string;
  },
  gcpSecret: Secret,
  supportsResetOnSameCommit: boolean,
  dependsOn: Resource[]
) {
  const sa = new k8s.core.v1.ServiceAccount(`${name}-sa`, {
    metadata: {
      name: `${name}-sa`,
      namespace: namespaceName,
    },
  });
  const crb = new k8s.rbac.v1.ClusterRoleBinding(`${name}:system:auth-delegator`, {
    roleRef: {
      apiGroup: 'rbac.authorization.k8s.io',
      kind: 'ClusterRole',
      name: 'system:auth-delegator',
    },
    subjects: [
      {
        kind: 'ServiceAccount',
        name: sa.metadata.name,
        namespace: sa.metadata.namespace,
      },
    ],
  });
  const crbAdmin = new k8s.rbac.v1.ClusterRoleBinding(`${name}:cluster-admin`, {
    roleRef: {
      apiGroup: 'rbac.authorization.k8s.io',
      kind: 'ClusterRole',
      name: 'cluster-admin',
    },
    subjects: [
      {
        kind: 'ServiceAccount',
        name: sa.metadata.name,
        namespace: sa.metadata.namespace,
      },
    ],
  });

  const privateConfigs = ref.config.privateConfigsDir
    ? {
        PRIVATE_CONFIGS_PATH: {
          type: 'Literal',
          literal: {
            value: `/share/source/${ref.config.privateConfigsDir}`,
          },
        },
      }
    : {};
  const publicConfigs = ref.config.publicConfigsDir
    ? {
        PUBLIC_CONFIGS_PATH: {
          type: 'Literal',
          literal: {
            value: `/share/source/${ref.config.publicConfigsDir}`,
          },
        },
      }
    : {};
  const pulumiVersion = spliceEnvConfig.requireEnv('PULUMI_VERSION');
  // required because the docker images are broken
  // TODO(#15978): remove this once pulumi is upgraded
  const minimumPulumiVersionRequired = '3.147.0';
  return new k8s.apiextensions.CustomResource(
    name,
    {
      apiVersion: 'pulumi.com/v1',
      kind: 'Stack',
      metadata: { name: name, namespace: namespaceName },
      spec: {
        ...{
          serviceAccountName: sa.metadata.name,
          stack: `organization/${projectName}/${name}.${CLUSTER_BASENAME}`,
          backend: config.requireEnv('PULUMI_BACKEND_URL'),
          envRefs: {
            ...envRefs,
            PULUMI_VERSION: {
              type: 'Literal',
              literal: {
                value: config.requireEnv('PULUMI_VERSION'),
              },
            },
            SPLICE_ROOT: {
              type: 'Literal',
              literal: {
                value: `/share/source/${ref.config.spliceRoot}`,
              },
            },
            DEPLOYMENT_DIR: {
              type: 'Literal',
              literal: {
                value: `/share/source/${ref.config.deploymentDir}`,
              },
            },
            ...privateConfigs,
            ...publicConfigs,
            GCP_CLUSTER_BASENAME: {
              type: 'Literal',
              literal: {
                value: CLUSTER_BASENAME,
              },
            },
            ...Object.keys(extraEnvs).reduce<{
              [key: string]: unknown;
            }>((acc, key) => {
              acc[key] = {
                type: 'Literal',
                literal: {
                  value: extraEnvs[key],
                },
              };
              return acc;
            }, {}),
          },
          fluxSource: {
            sourceRef: {
              apiVersion: ref.resource.apiVersion,
              kind: ref.resource.kind,
              name: ref.resource.metadata.name,
            },
            dir: `${ref.config.pulumiBaseDir}/${projectName}`,
          },
          // Do not resync the stack when the commit hash matches the last one
          continueResyncOnCommitMatch: false,
          destroyOnFinalize: false,
          // Enforce that the stack already exists
          useLocalStackOnly: true,
          // retry if the stack is locked by another operation
          retryOnUpdateConflict: true,
          // https://github.com/pulumi/pulumi-kubernetes-operator/blob/v2.1.0/docs/stacks.md#stackspecworkspacetemplatespec
          workspaceTemplate: {
            metadata: {
              name: `${name.replaceAll('.', '-')}`,
              namespace: namespaceName,
            },
            spec: {
              image: `pulumi/pulumi:${semver.gt(pulumiVersion, minimumPulumiVersionRequired) ? pulumiVersion : minimumPulumiVersionRequired}-nonroot`,
              env: [
                {
                  name: 'CN_PULUMI_LOAD_ENV_CONFIG_FILE',
                  value: 'true',
                },
                {
                  name: 'SPLICE_OPERATOR_DEPLOYMENT',
                  value: 'true',
                },
                {
                  // Avoids rate-limiting pulumi access of public repositories
                  name: 'GITHUB_TOKEN',
                  valueFrom: {
                    secretKeyRef: {
                      // This secret is created flux/github-secret.ts for the flux controller
                      name: 'github',
                      key: 'password',
                    },
                  },
                },
                {
                  name: 'CLOUDSDK_CORE_PROJECT',
                  value: config.requireEnv('CLOUDSDK_CORE_PROJECT'),
                },
                {
                  name: 'CLOUDSDK_COMPUTE_REGION',
                  value: config.requireEnv('CLOUDSDK_COMPUTE_REGION'),
                },
                {
                  name: 'GOOGLE_APPLICATION_CREDENTIALS',
                  value: '/app/gcp-credentials.json',
                },
                {
                  name: 'GOOGLE_CREDENTIALS',
                  valueFrom: {
                    secretKeyRef: {
                      name: gcpSecret.metadata.name,
                      key: 'googleCredentials',
                    },
                  },
                },
              ],
              podTemplate: {
                spec: {
                  ...infraAffinityAndTolerations,
                  volumes: [
                    {
                      name: 'gcp-credentials',
                      secret: {
                        secretName: gcpSecret.metadata.name,
                        optional: false,
                      },
                    },
                  ],
                  containers: [
                    {
                      name: 'pulumi',
                      volumeMounts: gcpSecret
                        ? [
                            {
                              name: 'gcp-credentials',
                              mountPath: '/app/gcp-credentials.json',
                              subPath: 'googleCredentials',
                            },
                          ]
                        : [],
                    },
                  ],
                },
              },
            },
          },
        },
        ...(supportsResetOnSameCommit
          ? {
              continueResyncOnCommitMatch: true,
              resyncFrequencySeconds: 300,
              // TODO(#16186): consider scaling down the operator instead
              refresh: true,
            }
          : {}),
      },
    },
    {
      dependsOn: dependsOn.concat([sa, crb, crbAdmin]),
    }
  );
}
