// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import { expect, jest, test } from '@jest/globals';
import { collectResources } from '@lfdecentralizedtrust/splice-pulumi-common/src/test';
import { z } from 'zod';

import { installRunnerScaleSets } from './runners';

jest.mock('./config', () => ({
  __esModule: true,
  ghaConfig: {
    githubRepo: 'https://dummy-gh-repo.com',
    runnerVersion: '1.2',
    runnerHookVersion: '1.1',
  },
}));
jest.mock('@lfdecentralizedtrust/splice-pulumi-common', () => ({
  __esModule: true,
  appsAffinityAndTolerations: {},
  DOCKER_REPO: 'https://dummy-docker-repo.com',
  HELM_MAX_HISTORY_SIZE: 42,
  GCP_REGION: 'us-central123',
  GCP_ZONE: 'some-wonderful-place',
  imagePullSecretByNamespaceNameForServiceAccount: () => [],
  infraAffinityAndTolerations: {},
  CloudSqlConfigSchema: z.object({ flags: z.record(z.string()).default({}) }),
  installPostgresPasswordSecret: () => {
    return { metadata: { name: 'secret' } };
  },
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

  // check that each k8s resource is in the gha-runners namespace
  for (const resource of resources) {
    // skip the gha-runners namespace itself
    if (k8s.core.v1.Namespace.isInstance(resource)) {
      continue;
    }

    const namespace: pulumi.Output<string> | undefined = getKubernetesNamespace(resource);
    // skip non-k8s resources
    if (namespace === undefined) {
      continue;
    }

    // check the namespace and return useful information on error
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

function getKubernetesNamespace(resource: pulumi.Resource): pulumi.Output<string> | undefined {
  if ('namespace' in resource) {
    return resource.namespace as pulumi.Output<string>;
  } else if ('metadata' in resource) {
    return (resource.metadata as pulumi.Output<k8s.types.output.meta.v1.ObjectMeta>).apply(
      meta => meta.namespace
    );
  } else {
    return undefined;
  }
}
