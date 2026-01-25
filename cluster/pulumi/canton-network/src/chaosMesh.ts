// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import {
  DecentralizedSynchronizerUpgradeConfig,
  GCP_PROJECT,
  HELM_MAX_HISTORY_SIZE,
  infraAffinityAndTolerations,
} from '@lfdecentralizedtrust/splice-pulumi-common';
import { Resource } from '@pulumi/pulumi';

export type ChaosMeshArguments = {
  dependsOn: Resource[];
};

export const podKillSchedule = (
  chaosMeshNs: k8s.core.v1.Namespace,
  appName: string,
  appNs: string,
  dependsOn: Resource[]
): k8s.apiextensions.CustomResource =>
  new k8s.apiextensions.CustomResource(
    `kill-pod-${appNs}-${appName}`,
    {
      apiVersion: 'chaos-mesh.org/v1alpha1',
      kind: 'Schedule',
      metadata: {
        name: `kill-${appNs}-${appName}`,
        namespace: chaosMeshNs.metadata.name,
      },
      spec: {
        // TODO(DACH-NY/canton-network-node#10689) Reduce this back to 5min once Canton sequencers stop being so slow
        schedule: '@every 60m',
        historyLimit: 2,
        concurrencyPolicy: 'Forbid',
        type: 'PodChaos',
        podChaos: {
          action: 'pod-kill',
          mode: 'one',
          selector: {
            labelSelectors: {
              app: appName,
            },
            namespaces: [appNs],
          },
        },
      },
    },
    { dependsOn }
  );

export const installChaosMesh = ({ dependsOn }: ChaosMeshArguments): k8s.helm.v3.Release => {
  // chaos-mesh needs custom permissions https://chaos-mesh.org/docs/faqs/#the-default-administrator-google-cloud-user-account-is-forbidden-to-create-chaos-experiments-how-to-fix-it
  const role = new k8s.rbac.v1.ClusterRole('chaos-mesh-role', {
    metadata: {
      name: 'chaos-mesh-role',
    },
    rules: [
      { apiGroups: [''], resources: ['pods', 'namespaces'], verbs: ['get', 'watch', 'list'] },
      {
        apiGroups: ['chaos-mesh.org'],
        resources: ['*'],
        verbs: ['get', 'list', 'watch', 'create', 'delete', 'patch', 'update'],
      },
    ],
  });
  const roleBinding = new k8s.rbac.v1.ClusterRoleBinding(
    'chaos-mesh-role-binding',
    {
      metadata: {
        name: 'chaos-mesh-role-binding',
      },
      subjects: [
        {
          kind: 'User',
          name: 'fayimora.femibalogun@digitalasset.com',
        },
        {
          kind: 'User',
          name: 'itai.segall@digitalasset.com',
        },
        {
          kind: 'User',
          name: 'julien.tinguely@digitalasset.com',
        },
        {
          kind: 'User',
          name: 'martin.florian@digitalasset.com',
        },
        {
          kind: 'User',
          name: 'moritz.kiefer@digitalasset.com',
        },
        {
          kind: 'User',
          name: 'nicu.reut@digitalasset.com',
        },
        {
          kind: 'User',
          name: 'oriol.munoz@digitalasset.com',
        },
        {
          kind: 'User',
          name: 'raymond.roestenburg@digitalasset.com',
        },
        {
          kind: 'User',
          name: 'robert.autenrieth@digitalasset.com',
        },
        {
          kind: 'User',
          name: 'simon@digitalasset.com',
        },
        {
          kind: 'User',
          name: 'stephen.compall@digitalasset.com',
        },
        // Pulumi does some weird magic that is different from `kubectl delete`
        // and ends up requiring permissions for the garbage collector to tear
        // down the chaos mesh schedule.
        {
          kind: 'User',
          name: 'system:serviceaccount:kube-system:generic-garbage-collector',
        },
        {
          kind: 'User',
          name: `circleci@${GCP_PROJECT}.iam.gserviceaccount.com`,
        },
      ],
      roleRef: {
        kind: 'ClusterRole',
        name: 'chaos-mesh-role',
        apiGroup: 'rbac.authorization.k8s.io',
      },
    },
    { dependsOn: [role] }
  );

  const ns = new k8s.core.v1.Namespace('chaos-mesh', {
    metadata: {
      name: 'chaos-mesh',
    },
  });
  const chaosMesh = new k8s.helm.v3.Release(
    'chaos-mesh',
    {
      name: 'chaos-mesh',
      chart: 'chaos-mesh',
      version: '2.7.2',
      namespace: ns.metadata.name,
      repositoryOpts: {
        repo: 'https://charts.chaos-mesh.org',
      },
      values: {
        controllerManager: {
          ...infraAffinityAndTolerations,
        },
        chaosDaemon: {
          ...infraAffinityAndTolerations,
        },
        dashboard: {
          ...infraAffinityAndTolerations,
        },
        dnsServer: {
          ...infraAffinityAndTolerations,
        },
        maxHistory: HELM_MAX_HISTORY_SIZE,
      },
    },
    {
      dependsOn: [ns],
    }
  );
  [
    `global-domain-${DecentralizedSynchronizerUpgradeConfig.active.id}-cometbft`,
    `global-domain-${DecentralizedSynchronizerUpgradeConfig.active.id}-mediator`,
    `global-domain-${DecentralizedSynchronizerUpgradeConfig.active.id}-sequencer`,
    `participant-${DecentralizedSynchronizerUpgradeConfig.active.id}`,
    'scan-app',
    'sv-app',
    'validator-app',
  ].forEach(name => podKillSchedule(ns, name, 'sv-4', [roleBinding, ...dependsOn]));
  return chaosMesh;
};
