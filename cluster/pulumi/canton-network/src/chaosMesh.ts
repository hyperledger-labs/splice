// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import {
  appsAffinityAndTolerations,
  clusterYamlConfig,
  DecentralizedSynchronizerUpgradeConfig,
  GCP_PROJECT,
  HELM_MAX_HISTORY_SIZE,
  infraAffinityAndTolerations,
} from '@lfdecentralizedtrust/splice-pulumi-common';
import { Resource } from '@pulumi/pulumi';
import { z } from 'zod';

const chaosMeshSchema = z.object({
  chaosMesh: z
    .object({
      dabftLatency: z.string().optional(),
    })
    .optional(),
});

const config = chaosMeshSchema.parse(clusterYamlConfig);

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

// Injecting latency between the DABFT nodes is a bit convoluted.
// Chaos Mesh does support specifying a target pod so naturally we would apply it to all traffic between two sequencers.
// However, this only works if you talk to the pod directly. In our case, we use the external URL of the other sequencer which then gets routed through the istio loopback
// and bypasses that.
// Istio also has an option to add latency but it is not supported on https virtualservices which we have for the DABFT traffic.
// Therefore we instead inject the latency from requests to the ingress pod to the sequencer. However,
// we only want it to apply to DABFT traffic not the full sequencer ingress. You cannot specify a port when specifying targets https://github.com/chaos-mesh/chaos-mesh/issues/2295
// so we have to use externalTargets which does support pods. However, using a name like global-domain-0-sequencer.sv-3 will resolve to the service IP not the pod IP so it also won't apply.
// Therefore we just apply a wildcard to all IPs for port 5010 and hope nothing else runs on port 5010. ipset does not like a full 0.0.0.0/0 wildcard so we
// instead use 10.0.0.0/8.
export const dabftLatency = (
  chaosMeshNs: k8s.core.v1.Namespace,
  latency: string,
  dependsOn: Resource[]
): k8s.apiextensions.CustomResource =>
  new k8s.apiextensions.CustomResource(
    `dabft-latency`,
    {
      apiVersion: 'chaos-mesh.org/v1alpha1',
      kind: 'NetworkChaos',
      metadata: {
        name: 'dabft-latency',
        namespace: chaosMeshNs.metadata.name,
      },
      spec: {
        action: 'delay',
        // run on all pods that match the selector
        mode: 'all',
        selector: {
          namespaces: ['cluster-ingress'],
          labelSelectors: {
            app: 'istio-ingress',
          },
        },
        delay: {
          latency,
        },
        direction: 'to',
        externalTargets: ['10.0.0.0/8:5010'],
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
          // the chaos-daemon needs to run on the apps nodes to be able to inject latency
          tolerations: [
            ...infraAffinityAndTolerations.tolerations,
            ...appsAffinityAndTolerations.tolerations,
          ],
          affinity: {
            nodeAffinity: {
              requiredDuringSchedulingIgnoredDuringExecution: {
                nodeSelectorTerms: [
                  ...infraAffinityAndTolerations.affinity.nodeAffinity
                    .requiredDuringSchedulingIgnoredDuringExecution.nodeSelectorTerms,
                  ...appsAffinityAndTolerations.affinity.nodeAffinity
                    .requiredDuringSchedulingIgnoredDuringExecution.nodeSelectorTerms,
                ],
              },
            },
          },
          runtime: 'containerd',
          socketPath: '/run/containerd/containerd.sock',
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
  if (config.chaosMesh?.dabftLatency) {
    dabftLatency(ns, config.chaosMesh.dabftLatency, [roleBinding, ...dependsOn]);
  }
  return chaosMesh;
};
