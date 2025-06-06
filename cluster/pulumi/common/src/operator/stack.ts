import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import { CLUSTER_BASENAME, config, isMainNet } from 'splice-pulumi-common';

import { GitFluxRef } from './flux-source';

export type EnvRefs = { [key: string]: unknown };

export function createEnvRefs(envSecretName: string, namespaceName: string = 'operator'): EnvRefs {
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
          // Do not destroy the stack when the CR is deleted
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
              // TODO(#16186): consider scaling down the operator instead
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
