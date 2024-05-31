import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import { CLUSTER_BASENAME, config } from 'cn-pulumi-common';

import { gitRepo } from '../flux';
import { namespace } from '../namespace';
import { operator } from '../operator';

const requiredEnvs = Array.from([
  'SLACK_ACCESS_TOKEN',
  'AUTH0_CN_MANAGEMENT_API_CLIENT_ID',
  'AUTH0_CN_MANAGEMENT_API_CLIENT_SECRET',
  'AUTH0_SV_MANAGEMENT_API_CLIENT_ID',
  'AUTH0_SV_MANAGEMENT_API_CLIENT_SECRET',
  'AUTH0_VALIDATOR_MANAGEMENT_API_CLIENT_ID',
  'AUTH0_VALIDATOR_MANAGEMENT_API_CLIENT_SECRET',
  'ARTIFACTORY_USER',
  'ARTIFACTORY_PASSWORD',
]);
const optionalEnvs = Array.from([
  'K6_USERS_PASSWORD',
  'K6_VALIDATOR_ADMIN_PASSWORD',
  'AUTH0_MAIN_MANAGEMENT_API_CLIENT_SECRET',
  'AUTH0_MAIN_MANAGEMENT_API_CLIENT_ID',
]);

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

const envSecret = new k8s.core.v1.Secret('env', {
  metadata: {
    name: 'env',
    namespace: namespace.ns.metadata.name,
  },
  type: 'Opaque',
  stringData: env,
});

const envRefs: {
  [key: string]: unknown;
} = {};
Object.keys(env).forEach(key => {
  envRefs[key] = {
    type: 'Secret',
    secret: {
      name: envSecret.metadata.name,
      key: key,
    },
  };
});

/*https://github.com/pulumi/pulumi-kubernetes-operator/blob/master/docs/stacks.md*/
export function createStackCR(
  name: string,
  supportsResetOnSameCommit: boolean
): pulumi.CustomResource {
  return new k8s.apiextensions.CustomResource(
    name,
    {
      apiVersion: 'pulumi.com/v1',
      kind: 'Stack',
      metadata: { name: name, namespace: namespace.logicalName },
      spec: {
        ...{
          stack: `organization/${name}/${name}.${CLUSTER_BASENAME}`,
          backend: config.requireEnv('PULUMI_BACKEND_URL'),
          envRefs: {
            ...envRefs,
            REPO_ROOT: {
              type: 'Literal',
              literal: {
                value: `/tmp/pulumi-working/operator/${name}/workspace`,
              },
            },
            GCP_CLUSTER_BASENAME: {
              type: 'Literal',
              literal: {
                value: CLUSTER_BASENAME,
              },
            },
          },
          fluxSource: {
            sourceRef: {
              apiVersion: gitRepo.apiVersion,
              kind: gitRepo.kind,
              name: gitRepo.metadata.name,
            },
            dir: `cluster/pulumi/${name}`,
          },
          // Do not resync the stack when the commit hash matches the last one
          continueResyncOnCommitMatch: false,
          // Do not destroy the stack when the CR is deleted
          destroyOnFinalize: false,
          // Refresh before every sync
          refresh: false,
          // Enforce that the stack already exists
          useLocalStackOnly: true,
          // retry if the stack is locked by another operation
          retryOnUpdateConflict: true,
        },
        ...(supportsResetOnSameCommit
          ? {
              continueResyncOnCommitMatch: true,
              resyncFrequencySeconds: 300,
            }
          : {}),
      },
    },
    {
      dependsOn: [operator],
    }
  );
}
