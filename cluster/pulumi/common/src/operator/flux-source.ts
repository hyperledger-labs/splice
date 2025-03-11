import * as k8s from '@pulumi/kubernetes';
import { Resource } from '@pulumi/pulumi';

export type GitFluxRef = k8s.apiextensions.CustomResource;

// https://github.com/fluxcd/source-controller/blob/main/docs/spec/v1/gitrepositories.md
export function gitRepoForRef(
  nameSuffix: string,
  ref: string,
  dependsOn: Resource[] = []
): GitFluxRef {
  return new k8s.apiextensions.CustomResource(
    `splice-node-${nameSuffix}`,
    {
      apiVersion: 'source.toolkit.fluxcd.io/v1',
      kind: 'GitRepository',
      metadata: { name: `splice-node-${nameSuffix}`, namespace: 'operator' },
      spec: {
        interval: '5m',
        url: 'https://github.com/DACH-NY/canton-network-node',
        ref: {
          name: ref,
        },
        secretRef: { name: 'github' },
        recurseSubmodules: true,
      },
    },
    {
      dependsOn: dependsOn,
    }
  );
}
