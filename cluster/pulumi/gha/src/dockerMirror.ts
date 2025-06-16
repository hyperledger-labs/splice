// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import { Namespace } from '@pulumi/kubernetes/core/v1';
import { infraAffinityAndTolerations } from 'splice-pulumi-common';

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
        repo: 'https://helm.twun.io',
      },
      values: {
        proxy: {
          // Configure the registry to act as a read-through cache for the Docker Hub.
          enabled: true,
        },
        persistence: {
          enabled: true,
        },
        ...infraAffinityAndTolerations,
      },
    },
    {
      dependsOn: [namespace],
    }
  );
}
