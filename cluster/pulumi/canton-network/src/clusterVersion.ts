// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import {
  activeVersion,
  CLUSTER_HOSTNAME,
  config,
} from '@lfdecentralizedtrust/splice-pulumi-common';
import { exactNamespace } from '@lfdecentralizedtrust/splice-pulumi-common/src/namespace';
import exec from 'node:child_process';

export function installClusterVersion(): k8s.apiextensions.CustomResource {
  const ns = exactNamespace('cluster-version', true);
  const host = CLUSTER_HOSTNAME;
  const remoteVersion = activeVersion.type == 'remote' ? activeVersion.version : undefined;
  const version =
    remoteVersion ||
    // cannot be used with the operator
    exec
      .execSync(`${config.requireEnv('SPLICE_ROOT')}/build-tools/get-snapshot-version`, {
        env: {
          // eslint-disable-next-line no-process-env
          ...process.env,
          CI_IGNORE_DIRTY_REPO: '1',
        },
      })
      .toString();
  return new k8s.apiextensions.CustomResource(
    `cluster-version-virtual-service`,
    {
      apiVersion: 'networking.istio.io/v1alpha3',
      kind: 'VirtualService',
      metadata: {
        name: 'cluster-version',
        namespace: ns.ns.metadata.name,
      },
      spec: {
        hosts: [host],
        gateways: ['cluster-ingress/cn-http-gateway'],
        http: [
          {
            match: [
              {
                port: 443,
                uri: { exact: '/version' },
              },
            ],
            directResponse: {
              status: 200,
              body: { string: version },
            },
          },
        ],
      },
    },
    { deleteBeforeReplace: true, dependsOn: [ns.ns] }
  );
}
