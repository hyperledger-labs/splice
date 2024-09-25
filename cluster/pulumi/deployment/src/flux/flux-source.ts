import * as k8s from '@pulumi/kubernetes';
import { config } from 'splice-pulumi-common';

import { namespace } from '../namespace';
import { flux } from './flux';

const githubSecret = new k8s.core.v1.Secret('github', {
  metadata: {
    name: 'github',
    namespace: namespace.ns.metadata.name,
  },
  type: 'Opaque',
  stringData: {
    username: config.optionalEnv('GITHUB_USERNAME') || 'canton-network-da',
    password: config.requireEnv('GITHUB_TOKEN'),
  },
});

export type GitFluxRef = k8s.apiextensions.CustomResource;

// https://github.com/fluxcd/source-controller/blob/main/docs/spec/v1/gitrepositories.md
export function gitRepoForRef(nameSuffix: string, ref: string): GitFluxRef {
  return new k8s.apiextensions.CustomResource(
    `splice-node-${nameSuffix}`,
    {
      apiVersion: 'source.toolkit.fluxcd.io/v1',
      kind: 'GitRepository',
      metadata: { name: `splice-node-${nameSuffix}`, namespace: namespace.logicalName },
      spec: {
        interval: '5m',
        url: 'https://github.com/DACH-NY/canton-network-node',
        ref: {
          name: ref,
        },
        secretRef: { name: githubSecret.metadata.name },
        recurseSubmodules: true,
      },
    },
    { dependsOn: [flux] }
  );
}
