// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import {
  CACHE_GHCR,
  DockerConfig,
  HELM_MAX_HISTORY_SIZE,
  infraKubernetesScheduling,
} from '@canton-network/splice-pulumi-common';
import { Namespace } from '@pulumi/kubernetes/core/v1';

import { ghaConfig } from './config';

export function installController(repo: string, runnersNamespaceName: string): k8s.helm.v3.Release {
  const controllerNamespaceName = `gha-runner-controller-${repo}`;
  const controllerNamespace = new Namespace(controllerNamespaceName, {
    metadata: {
      name: controllerNamespaceName,
    },
  });

  const imagePullSecret = DockerConfig.getConfig().createImagePullSecret(
    controllerNamespaceName,
    'docker-reg-cred',
    [controllerNamespace]
  );

  const releaseName = repo == 'splice' ? 'gha-runner-scale-set-controller-splice' : `ssc-${repo}`;
  return new k8s.helm.v3.Release(
    releaseName,
    {
      chart:
        'oci://ghcr.io/actions/actions-runner-controller-charts/gha-runner-scale-set-controller',
      version: ghaConfig.runnerScaleSetVersion,
      namespace: controllerNamespace.metadata.name,
      values: {
        ...infraKubernetesScheduling,
        maxHistory: HELM_MAX_HISTORY_SIZE,
        image: {
          repository: `${CACHE_GHCR}/actions/gha-runner-scale-set-controller`,
        },
        imagePullSecrets: [{ name: imagePullSecret.metadata.name }],
        flags: {
          logFormat: 'json',
          watchSingleNamespace: runnersNamespaceName,
        },
      },
    },
    { dependsOn: [imagePullSecret] }
  );
}
