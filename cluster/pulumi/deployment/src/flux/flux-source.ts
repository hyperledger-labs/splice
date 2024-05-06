import * as k8s from '@pulumi/kubernetes';
import { config } from 'cn-pulumi-common';

import { namespace } from '../namespace';
import { flux } from './flux';

const githubSecret = new k8s.core.v1.Secret('github', {
  metadata: {
    name: 'github',
    namespace: namespace.ns.metadata.name,
  },
  type: 'Opaque',
  stringData: {
    username: config.optionalEnv('GITHUB_USERNAME') || 'githubuser-da',
    password: config.requireEnv('GITHUB_TOKEN'),
  },
});
// https://github.com/fluxcd/source-controller/blob/main/docs/spec/v1/gitrepositories.md
export const gitRepo = new k8s.apiextensions.CustomResource(
  'splice-node',
  {
    apiVersion: 'source.toolkit.fluxcd.io/v1',
    kind: 'GitRepository',
    metadata: { name: 'splice-node', namespace: namespace.logicalName },
    spec: {
      interval: '5m',
      url: 'https://github.com/DACH-NY/canton-network-node',
      ref: {
        name: config.requireEnv('CN_DEPLOYMENT_FLUX_REF'),
      },
      secretRef: { name: githubSecret.metadata.name },
    },
  },
  { dependsOn: [flux] }
);
