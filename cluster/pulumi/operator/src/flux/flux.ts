import * as k8s from '@pulumi/kubernetes';
import { HELM_MAX_HISTORY_SIZE, infraAffinityAndTolerations } from 'splice-pulumi-common';

import { namespace } from '../namespace';

export const flux = new k8s.helm.v3.Release('flux', {
  name: 'flux',
  chart: 'flux2',
  version: '2.12.4',
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
