// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';
import { expect, jest, test } from '@jest/globals';

import { installClusterVersion } from './clusterVersion';

jest.mock('@lfdecentralizedtrust/splice-pulumi-common', () => ({
  __esModule: true,
  activeVersion: {
    type: 'remote',
    version: '1.0.0',
  },
  CLUSTER_HOSTNAME: 'cluster.global',
  config: {},
}));

test('version service should return the correct version', async () => {
  await pulumi.runtime.setMocks({
    newResource(args) {
      return {
        id: `mock:${args.name}`,
        state: args.inputs,
      };
    },
    call(args) {
      switch (args.token) {
        default:
          return args.inputs;
      }
    },
  });

  const versionService = installClusterVersion();

  // check the version service spec for the correct version endpoint configuration
  const spec =
    'spec' in versionService
      ? (versionService.spec as pulumi.Output<{
          hosts: Array<string>;
          http: Array<{
            match: Array<{
              uri: { exact: string };
              port: number;
            }>;
            directResponse: {
              status: number;
              body: string;
            };
          }>;
        }>)
      : undefined;
  expect(spec).toBeDefined();
  spec?.apply(spec => {
    const route = spec.http.find(route =>
      route.match.some(match => match.port === 443 && match.uri.exact === '/version')
    );
    expect(route).toBeDefined();
    expect(route?.directResponse.status).toEqual(200);
    expect(route?.directResponse.body).toEqual({ string: '1.0.0' });
  });

  await pulumi.runtime.disconnect();
});
