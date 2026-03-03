// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import { infraAffinityAndTolerations } from '@lfdecentralizedtrust/splice-pulumi-common';
import { Namespace } from '@pulumi/kubernetes/core/v1';

export function installDockerRegistryMirror(): k8s.helm.v3.Release {
  const namespace = new Namespace('docker-mirror', {
    metadata: {
      name: 'docker-mirror',
    },
  });

  return new k8s.helm.v3.Release(
    'docker-registry-mirror',
    {
      name: 'docker-registry-mirror',
      chart: 'docker-registry',
      version: '2.3.0',
      namespace: namespace.metadata.name,
      repositoryOpts: {
        repo: 'https://twuni.github.io/docker-registry.helm',
      },
      values: {
        proxy: {
          // Configure the registry to act as a read-through cache for the Docker Hub.
          enabled: true,
          // Keep cached images for 30 days before expiring them (default: 168h / 7 days).
          ttl: '720h',
        },
        persistence: {
          enabled: true,
          size: '20Gi',
        },
        configData: {
          // Enable blob/manifest deletion so the proxy's built-in TTL-based
          // scheduler can remove expired cached content.
          // See: https://distribution.github.io/distribution/recipes/mirror/
          storage: {
            delete: {
              enabled: true,
            },
          },
        },
        garbageCollect: {
          // Run periodic garbage collection to reclaim space from
          // unreferenced blobs after the proxy scheduler expires them.
          enabled: true,
          deleteUntagged: true,
          schedule: '0 1 * * *',
        },
        ...infraAffinityAndTolerations,
      },
    },
    {
      dependsOn: [namespace],
    }
  );
}
