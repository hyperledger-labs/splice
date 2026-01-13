// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import { expect, jest, test } from '@jest/globals';
import { collectResources } from '@lfdecentralizedtrust/splice-pulumi-common/src/test';

import { installRunnerScaleSets } from './runners';

jest.mock('./config', () => ({
  __esModule: true,
  ghaConfig: {
    githubRepo: 'dummy',
    runnerVersion: 'dummy',
    runnerHookVersion: 'dummy',
  },
}));
jest.mock('@lfdecentralizedtrust/splice-pulumi-common', () => ({
  __esModule: true,
  appsAffinityAndTolerations: {},
  DOCKER_REPO: 'dummy',
  HELM_MAX_HISTORY_SIZE: 42,
  imagePullSecretByNamespaceNameForServiceAccount: () => [],
  infraAffinityAndTolerations: {},
}));
jest.mock('@lfdecentralizedtrust/splice-pulumi-common/src/config/envConfig', () => ({
  __esModule: true,
  spliceEnvConfig: {
    requireEnv() {
      return 'dummy';
    },
  },
}));

test('GHA runner k8s resources are in the gha-runners namespace', async () => {
  await pulumi.runtime.setMocks({
    newResource(args) {
      return {
        id: `mock:${args.name}`,
        state: args.inputs,
      };
    },
    call(args) {
      switch (args.token) {
        case 'gcp:secretmanager/getSecretVersion:getSecretVersion':
          return {
            ...args.inputs,
            secretData: '{}',
          };
        default:
          return args.inputs;
      }
    },
  });
  const mockController = {
    __pulumiResource: true,
    name: 'mock-controller',
  } as unknown as k8s.helm.v3.Release;

  const [, resources] = await collectResources(() => {
    installRunnerScaleSets(mockController);
  });

  for (const resource of resources) {
    if (k8s.core.v1.Namespace.isInstance(resource)) {
      continue;
    }
    let namespace: pulumi.Output<string>;
    if ('namespace' in resource) {
      namespace = resource.namespace as pulumi.Output<string>;
    } else if ('metadata' in resource) {
      namespace = (resource.metadata as pulumi.Output<k8s.types.output.meta.v1.ObjectMeta>).apply(
        meta => meta.namespace
      );
    } else {
      continue;
    }
    pulumi.all([resource.urn, namespace]).apply(([urn, namespace]) => {
      try {
        expect(namespace).toEqual('gha-runners');
      } catch (error) {
        throw new Error(
          `Resource [${urn}] should be created in namespace [gha-runners] but is in [${namespace}].`
        );
      }
    });
  }

  await pulumi.runtime.disconnect();
});
