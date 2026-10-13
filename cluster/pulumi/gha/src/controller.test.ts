// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import { collectResources } from '@canton-network/splice-pulumi-common/src/test';
import { expect, jest, test } from '@jest/globals';

import { installController } from './controller';

jest.mock('./config', () => ({
  __esModule: true,
  ghaConfig: {
    runnerScaleSetVersion: '1.1',
  },
}));
jest.mock('@canton-network/splice-pulumi-common', () => ({
  __esModule: true,
  CACHE_GHCR: 'dummy-ghcr-mirror.com',
  DockerConfig: jest.requireActual<
    typeof import('@canton-network/splice-pulumi-common/src/dockerConfig')
  >('@canton-network/splice-pulumi-common/src/dockerConfig').DockerConfig,
  HELM_MAX_HISTORY_SIZE: 42,
  infraKubernetesScheduling: {},
}));

function outputValue<T>(output: pulumi.Output<T>): Promise<T> {
  return new Promise(resolve => output.apply(resolve));
}

test('GHA controller pulls its mirrored image with an image pull secret from its own namespace', async () => {
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

  const [controller, resources] = await collectResources(() =>
    installController('test-repo', 'gha-runners-test-repo')
  );

  const secrets = resources.filter(r => k8s.core.v1.Secret.isInstance(r)) as k8s.core.v1.Secret[];
  expect(secrets).toHaveLength(1);
  const [secret] = secrets;
  const secretMetadata = await outputValue(secret.metadata);
  expect(secretMetadata.namespace).toEqual('gha-runner-controller-test-repo');
  expect(await outputValue(secret.type)).toEqual('kubernetes.io/dockerconfigjson');

  const values = await outputValue(controller.values);
  expect(await outputValue(controller.namespace)).toEqual('gha-runner-controller-test-repo');
  expect(values['image']).toEqual({
    repository: 'dummy-ghcr-mirror.com/actions/gha-runner-scale-set-controller',
  });
  expect(values['imagePullSecrets']).toEqual([{ name: secretMetadata.name }]);

  await pulumi.runtime.disconnect();
});
