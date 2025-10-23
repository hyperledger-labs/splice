// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import { Resource } from '@pulumi/pulumi';

export type GitReferenceConfig = {
  repoUrl: string;
  gitReference: string;
  pulumiStacksDir: string;
  pulumiBaseDir: string;
  deploymentDir: string;
  spliceRoot: string;
  privateConfigsDir?: string;
  publicConfigsDir?: string;
};
export type GitFluxRef = {
  resource: k8s.apiextensions.CustomResource;
  config: GitReferenceConfig;
};
export type StackFromRef = { project: string; stack: string };

// Trim non-splitwell DARs to avoid blowing the hardcoded operator size limit of 50mb
const repoIgnore = '**/daml/dars\n!**/daml/dars/splitwell*';

function expandGitReference(gitReference: string): { name: string; } | { commit: string; } {
  if(gitReference.startsWith('refs/')) {
    return { name: gitReference };
  } else {
    return { commit: gitReference };
  }
}

// https://github.com/fluxcd/source-controller/blob/main/docs/spec/v1/gitrepositories.md
export function gitRepoForRef(
  nameSuffix: string,
  ref: GitReferenceConfig,
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
          url: ref.repoUrl,
          ref: expandGitReference(ref.gitReference),
          secretRef: { name: 'github' },
          recurseSubmodules: true,
          ignore: repoIgnore,
        },
      },
      {
        dependsOn: dependsOn,
      }
    );
  }
  const resource = new k8s.apiextensions.CustomResource(
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
        url: ref.repoUrl,
        ref: expandGitReference(ref.gitReference),
        include: stacksToCopy.map(stack => ({
          fromPath: `${ref.pulumiStacksDir}/${stack.project}/Pulumi.${stack.stack}.yaml`,
          toPath: `${ref.pulumiBaseDir}/${stack.project}/Pulumi.${stack.stack}.yaml`,
          repository: {
            name: `splice-node-${nameSuffix}-base`,
          },
        })),
        ignore: repoIgnore,
        secretRef: { name: 'github' },
        recurseSubmodules: true,
      },
    },
    {
      dependsOn: dependsOn,
    }
  );

  return {
    resource: resource,
    config: ref,
  };
}
