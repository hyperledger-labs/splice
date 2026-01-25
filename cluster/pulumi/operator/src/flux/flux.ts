// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import {
  HELM_MAX_HISTORY_SIZE,
  infraAffinityAndTolerations,
} from '@lfdecentralizedtrust/splice-pulumi-common';

import { namespace } from '../namespace';

export const flux = new k8s.helm.v3.Release('flux', {
  name: 'flux',
  chart: 'flux2',
  // When trying to upgrade to 2.15.0 and 2.16.2, source-controller failed to pull the code, with an error of git not being found in PATH
  // (source-controller does not use git from PATH, but a built-in git implementation, so this seems to be a symptom of some other failure,
  //  perhaps authentication to the private repo), so for now we do not upgrade past 2.14.1.
  version: '2.14.1',
  namespace: namespace.ns.metadata.name,
  repositoryOpts: {
    repo: 'https://fluxcd-community.github.io/helm-charts',
  },
  values: {
    cli: {
      ...infraAffinityAndTolerations,
    },
    notificationController: {
      ...infraAffinityAndTolerations,
    },
    sourceController: {
      ...infraAffinityAndTolerations,
    },
    helmController: {
      create: false,
    },
    imageAutomationController: {
      create: false,
    },
    imageReflectionController: {
      create: false,
    },
    kustomizeController: {
      create: false,
    },
    prometheus: {
      podMonitor: {
        create: true,
      },
    },
  },
  maxHistory: HELM_MAX_HISTORY_SIZE,
});
