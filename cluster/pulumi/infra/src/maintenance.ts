// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import { Input } from '@pulumi/pulumi';

const deleteBadPodsCommand = [
  '/bin/sh',
  '-c',
  `kubectl get pods -A --field-selector=status.phase!=Running -o custom-columns=NAMESPACE:.metadata.namespace,NAME:.metadata.name,STATUS:.status.phase,REASON:.status.containerStatuses[*].state.waiting.reason | \\
  grep -E "Error|Unknown|CrashLoopBackOff" | \\
  awk '{print $1, $2}' | \\
  xargs -n 2 sh -c 'kubectl delete pod -n "$0" "$1"' _ || true`,
];

export function installPodCleanupCronJob(namespace: Input<string>): k8s.batch.v1.CronJob {
  const name = 'bad-pod-cleaner';
  const labels = { app: name, environment: pulumi.getStack() };

  // grants cluster-level permissions (get, list, delete for pods) regardless of namespace.
  const podCleanerClusterRole = new k8s.rbac.v1.ClusterRole(`${name}-clusterrole`, {
    metadata: {
      name: `${name}-clusterrole`,
      labels: labels,
    },
    rules: [
      {
        apiGroups: [''], // Core API group for 'pods'
        resources: ['pods'],
        verbs: ['get', 'list', 'delete'], // The minimum permissions required
      },
    ],
  });

  // Binds the role to the service account, enabling cluster-wide actions for that SA.
  new k8s.rbac.v1.ClusterRoleBinding(`${name}-crb`, {
    metadata: {
      name: `${name}-crb`,
      labels: labels,
    },
    subjects: [
      {
        kind: 'ServiceAccount',
        name: 'default',
        namespace: namespace,
      },
    ],
    roleRef: {
      kind: 'ClusterRole',
      name: podCleanerClusterRole.metadata.name,
      apiGroup: 'rbac.authorization.k8s.io',
    },
  });

  return new k8s.batch.v1.CronJob(name, {
    metadata: {
      name: name,
      namespace: namespace,
      labels: labels,
      annotations: {
        'pulumi.com/description': 'A scheduled job to automatically clean up pods cluster-wide.',
      },
    },
    spec: {
      schedule: '1 * * * *', // Run every hour at the 1st minute
      jobTemplate: {
        spec: {
          template: {
            spec: {
              serviceAccountName: 'default',
              restartPolicy: 'OnFailure',
              containers: [
                {
                  name: name,
                  image: 'bitnami/kubectl:latest',
                  command: deleteBadPodsCommand,
                },
              ],
            },
          },
        },
      },
    },
  });
}
