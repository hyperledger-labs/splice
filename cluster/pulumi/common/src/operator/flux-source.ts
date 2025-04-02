import * as k8s from '@pulumi/kubernetes';
import { Resource } from '@pulumi/pulumi';

import { PULUMI_STACKS_DIR } from '../utils';

export type GitFluxRef = k8s.apiextensions.CustomResource;
export type StackFromRef = { project: string; stack: string };

// https://github.com/fluxcd/source-controller/blob/main/docs/spec/v1/gitrepositories.md
export function gitRepoForRef(
  nameSuffix: string,
  ref: string,
  stacksToCopy: StackFromRef[] = [],
  notifications: boolean = true,
  dependsOn: Resource[] = []
): GitFluxRef {
  if (stacksToCopy.length !== 0) {
    new k8s.apiextensions.CustomResource(
      `splice-node-${nameSuffix}-base`,
      {
        apiVersion: 'source.toolkit.fluxcd.io/v1',
        kind: 'GitRepository',
        metadata: {
          name: `splice-node-${nameSuffix}-base`,
          namespace: 'operator',
          labels: {
            notifications: 'false',
          },
        },
        spec: {
          interval: '5m',
          url: 'https://github.com/DACH-NY/canton-network-node',
          ref: {
            name: ref,
          },
          secretRef: { name: 'github' },
          recurseSubmodules: false,
        },
      },
      {
        dependsOn: dependsOn,
      }
    );
  }
  return new k8s.apiextensions.CustomResource(
    `splice-node-${nameSuffix}`,
    {
      apiVersion: 'source.toolkit.fluxcd.io/v1',
      kind: 'GitRepository',
      metadata: {
        name: `splice-node-${nameSuffix}`,
        namespace: 'operator',
        labels: {
          notifications: notifications ? 'true' : 'false',
        },
      },
      spec: {
        interval: '5m',
        url: 'https://github.com/DACH-NY/canton-network-node',
        ref: {
          name: ref,
        },
        include: stacksToCopy.map(stack => ({
          fromPath: `${PULUMI_STACKS_DIR}/${stack.project}/Pulumi.${stack.project}.${stack.stack}.yaml`,
          toPath: `cluster/pulumi/${stack.project}/Pulumi.${stack.project}.${stack.stack}.yaml`,
          repository: {
            name: `splice-node-${nameSuffix}-base`,
          },
        })),
        secretRef: { name: 'github' },
        recurseSubmodules: true,
      },
    },
    {
      dependsOn: dependsOn,
    }
  );
}
