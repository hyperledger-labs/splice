// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import {
  HELM_MAX_HISTORY_SIZE,
  infraAffinityAndTolerations,
} from '@lfdecentralizedtrust/splice-pulumi-common';
import { Namespace } from '@pulumi/kubernetes/core/v1';

import { ghaConfig } from './config';

export function installController(): k8s.helm.v3.Release {
  const controllerNamespace = new Namespace('gha-runner-controller', {
    metadata: {
      name: 'gha-runner-controller',
    },
  });

  return new k8s.helm.v3.Release('gha-runner-scale-set-controller', {
    chart: 'oci://ghcr.io/actions/actions-runner-controller-charts/gha-runner-scale-set-controller',
    version: ghaConfig.runnerScaleSetVersion,
    namespace: controllerNamespace.metadata.name,
    values: {
      ...infraAffinityAndTolerations,
      maxHistory: HELM_MAX_HISTORY_SIZE,
      flags: {
        logFormat: 'json',
      },
    },
  });
}
