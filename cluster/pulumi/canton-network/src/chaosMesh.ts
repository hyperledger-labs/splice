import * as k8s from '@pulumi/kubernetes';
import { Resource } from '@pulumi/pulumi';
import { GCP_PROJECT } from 'cn-pulumi-common';

export type ChaosMeshArguments = {
  dependsOn: Resource[];
};

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
      // TODO(#9932) Replace this by a google group.
      subjects: [
        {
          kind: 'User',
          name: 'alex.matson@digitalasset.com',
        },
        {
          kind: 'User',
          name: 'chunlok.ling@digitalasset.com',
        },
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
          name: 'oriol.munoz.princep@digitalasset.com',
        },
        {
          kind: 'User',
          name: 'parth.joshi@digitalasset.com',
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
      version: '2.6.3',
      namespace: ns.metadata.name,
      repositoryOpts: {
        repo: 'https://charts.chaos-mesh.org',
      },
      values: {
        controllerManager: {
          leaderElection: {
            enabled: false,
          },
        },
      },
    },
    {
      dependsOn: [ns],
    }
  );
  new k8s.apiextensions.CustomResource(
    `kill-pod-sv-4-cometbft`,
    {
      apiVersion: 'chaos-mesh.org/v1alpha1',
      kind: 'Schedule',
      metadata: {
        name: 'kill-sv4-cometbft',
        namespace: ns.metadata.name,
      },
      spec: {
        schedule: '@every 5m',
        historyLimit: 2,
        concurrencyPolicy: 'Forbid',
        type: 'PodChaos',
        podChaos: {
          action: 'pod-kill',
          mode: 'one',
          selector: {
            labelSelectors: {
              app: 'global-domain-0-cometbft',
            },
            namespaces: ['sv-4'],
          },
        },
      },
    },
    { dependsOn: [roleBinding, ...dependsOn] }
  );
  return chaosMesh;
};
